"""元clientのnull針分岐だけを解釈する。ゲームクラスのロード・実行はしない。"""
import hashlib
import re
import subprocess
import zipfile


def mappings(path):
    classes = {}
    for line in path.read_text().splitlines() if path.exists() else []:
        if line and not line.startswith((' ', '#')):
            source, actual = line[:-1].split(' -> ')
            classes[source] = actual
    return classes


class Bytecode:
    def __init__(self, client, mapping, javap):
        self.client, self.names, self.javap = client, mappings(mapping), javap
        self.cache = {}
        self.definitions = {}
        self.members = {}
        owner = None
        method = re.compile(r"(?:\d+:\d+:)?([^ ]+) ([^(]+)\(([^)]*)\)(?::\d+(?::\d+)?)? -> (.+)")
        for line in mapping.read_text().splitlines() if mapping.exists() else []:
            if line.startswith("#") or not line.strip():continue
            if not line.startswith(" "):
                owner=line[:-1].split(" -> ")[0]
            else:
                match=method.fullmatch(line.strip())
                if match:
                    desc="("+"".join(self.descriptor(x) for x in match[3].split(",") if x)+")"+self.descriptor(match[1])
                    self.members[(self.names[owner],match[4],desc)]=match[2]
                elif " -> " in line:
                    source,actual=line.strip().split(" -> ");kind,name=source.split(" ",1)
                    self.members[(self.names[owner],actual,self.descriptor(kind))]=name
        self.trace = []

    def descriptor(self, source):
        if source.endswith('[]'):return '['+self.descriptor(source[:-2])
        primitive={'void':'V','boolean':'Z','byte':'B','char':'C','short':'S','int':'I','long':'J','float':'F','double':'D'}
        return primitive[source] if source in primitive else 'L'+self.names.get(source,source).replace('.','/')+';'

    def member_name(self, owner, name, descriptor):
        return self.members.get((owner.replace('/','.'),name,descriptor),name)

    def normalize(self, owner, code):
        positions={row[0]:i for i,row in enumerate(code)};result=[]
        reverse={actual.replace('.','/'):source.replace('.','/') for source,actual in self.names.items()}
        for pos,op,arg,comment in code:
            if op.startswith('if') or op=='goto':arg=str(positions[int(arg)])
            elif arg.startswith('#'):arg=''
            for prefix in ('Method ','InterfaceMethod ','Field '):
                if comment.startswith(prefix) and ':' in comment:
                    reference,desc=comment[len(prefix):].split(':',1)
                    cls,_,member=reference.rpartition('.')
                    if not cls:cls=owner
                    comment=prefix+cls+'.'+self.member_name(cls,member,desc)+':'+desc
                    break
            comment=re.sub(r'L([^;]+);',lambda m:'L'+reverse.get(m[1],m[1])+';',comment)
            comment=re.sub(r'(?<=class )[^ ]+',lambda m:reverse.get(m[0],m[0]),comment)
            comment=re.sub(r'(?<=Method )[^ .]+(?=\.)|(?<=Field )[^ .]+(?=\.)|(?<=InterfaceMethod )[^ .]+(?=\.)',lambda m:reverse.get(m[0],m[0]),comment)
            result.append(' '.join(x for x in (op,arg,comment) if x))
        return result

    def name(self, suffix):
        matches = [actual for source, actual in self.names.items() if source.endswith('.' + suffix) and (suffix!='Items' or source in ('net.minecraft.world.item.Items','net.minecraft.item.Items'))]
        if len(matches) > 1:
            raise ValueError('クラス名が一意でありません: ' + suffix)
        if matches:return matches[0]
        with zipfile.ZipFile(self.client) as jar:
            matches=[n[:-6].replace('/','.') for n in jar.namelist() if n.endswith('/'+suffix+'.class') and (suffix!='Items' or n=='net/minecraft/world/item/Items.class')]
        if len(matches)!=1:raise ValueError('元JARにクラスが一意にありません: '+suffix)
        return matches[0]

    def read(self, owner):
        if owner not in self.cache:
            result = subprocess.run([self.javap, '-p', '-c', '-s', '-classpath', str(self.client), owner.replace('/', '.')], capture_output=True, text=True, check=True)
            methods, descriptor, code, header, definitions = {}, None, None, None, []
            for line in result.stdout.splitlines():
                if line.startswith('  ') and not line.startswith('   ') and (line.rstrip().endswith(');') or line.strip()=='static {};'):
                    header=line.strip()
                if line.strip().startswith('descriptor:'):
                    descriptor = line.split('descriptor:')[1].strip()
                elif line.strip() == 'Code:':
                    code = []; methods[descriptor] = methods.get(descriptor, []) + [code]
                    raw='<clinit>' if header=='static {};' else header.split('(')[0].split()[-1]
                    raw='<init>' if raw==owner.replace('/','.') else raw
                    definitions.append({'name':self.member_name(owner,raw,descriptor),'descriptor':descriptor,'code':code})
                elif code is not None:
                    match = re.match(r'\s+(\d+):\s+(\w+)(.*)', line)
                    if match:
                        operand, _, comment = match[3].strip().partition('// ')
                        code.append((int(match[1]), match[2], operand.strip(), comment))
            self.cache[owner] = (result.stdout, methods)
            self.definitions[owner] = definitions
        return self.cache[owner]

    def method(self, owner, descriptor, name=None):
        text, methods = self.read(owner)
        candidates = methods.get(descriptor, [])
        if name is not None:
            blocks = re.split(r'\n  (?=[^ ])', text)
            for block in blocks:
                if f' {name}(' in block or f' {owner.replace("/", ".")}(' in block:
                    if f'descriptor: {descriptor}\n' in block:
                        instructions=[]
                        for line in block.splitlines():
                            m=re.match(r'\s+(\d+):\s+(\w+)(.*)',line)
                            if m:
                                operand,_,comment=m[3].strip().partition('// ')
                                instructions.append((int(m[1]),m[2],operand.strip(),comment))
                        return instructions
        if len(candidates) != 1:
            raise ValueError(f'分岐メソッドが一意でありません: {owner} {descriptor}')
        return candidates[0]

    def evaluate(self, owner, code, locals_, depth=0, boolean=0):
        if depth > 4:
            raise ValueError('null分岐の呼出し深さが未対応です')
        stack=[]; positions={row[0]: i for i,row in enumerate(code)}; index=0
        for _ in range(100):
            pos,op,arg,comment=code[index]
            self.trace.append({'class':owner,'offset':pos,'opcode':op,'operand':arg,'reference':comment})
            index += 1
            if op in ('aload','iload') or re.fullmatch(r'[ai]load_[0-3]',op):
                stack.append(locals_[int(arg) if '_' not in op else int(op[-1])])
            elif op in ('astore','istore') or re.fullmatch(r'[ai]store_[0-3]',op):
                locals_[int(arg) if '_' not in op else int(op[-1])]=stack.pop()
            elif op in ('aconst_null','iconst_0','iconst_1','fconst_0'):
                stack.append(None if op=='aconst_null' else 1 if op=='iconst_1' else 0)
            elif op in ('ifnull','ifnonnull','ifeq','ifne'):
                value=stack.pop(); take=(value is None if op=='ifnull' else value is not None if op=='ifnonnull' else value==0 if op=='ifeq' else value!=0)
                if take:index=positions[int(arg)]
            elif op=='goto':index=positions[int(arg)]
            elif op=='getfield':
                if stack.pop() != 'default-stack':raise ValueError('既定stack以外のfieldを読みました')
                field=comment.removeprefix('Field ')
                descriptor=field.split(':')[1]
                if descriptor!='Z':
                    entity=descriptor.removeprefix('L').removesuffix(';')
                    source=next((name for name,actual in self.names.items() if actual==entity),entity.replace('/','.'))
                    if not source.endswith(('.Entity','.ItemFrame','.ItemFrameEntity')):
                        raise ValueError('entity表現以外のfieldです: '+field)
                    text,_=self.read(owner)
                    constructors=[block for block in re.split(r'\n  (?=[^ ])',text) if re.match(r'(?:public |private |protected )?'+re.escape(owner.replace('/','.'))+r'\(',block)]
                    if not constructors or any('putfield' in line and ('Field '+field) in line for block in constructors for line in block.splitlines()):
                        raise ValueError('entity表現fieldの既定nullを証明できません: '+field)
                if depth==0:raise ValueError('propertyから直接stack fieldを読みました')
                stack.append(boolean if descriptor=='Z' else None)
                self.trace[-1]['defaultField']=field

            elif op=='invokevirtual':
                ref=comment.removeprefix('Method '); target,desc=ref.split(':',1)
                target_owner,_,name=target.rpartition('.')
                if not target_owner:target_owner=owner
                count=len(re.findall(r'\[*(?:L[^;]+;|[ZBCSIJFD])',desc.split(')')[0][1:]))
                if count!=0 or stack.pop()!='default-stack':raise ValueError('null経路の未知の呼出しです: '+ref)
                if depth>0 and desc=='()Z':
                    if target_owner!=owner or self.member_name(target_owner,name,desc) not in ('isEmpty','is_empty'):
                        raise ValueError('entity getterの未知のboolean呼出しです: '+ref)
                    # entity getterのempty判定は両分岐を検査する。count/itemの評価は不要。
                    stack.append(boolean)
                    self.trace[-1]['bothBooleanBranchesChecked']=True
                else:
                    getter=self.method(target_owner,desc,name)
                    values=[self.evaluate(target_owner,getter,{0:'default-stack'},depth+1,choice) for choice in (0,1)]
                    if values[0]!=values[1]:raise ValueError('stack getterのnull契約が分岐で変わります')
                    stack.append(values[0])
            elif op in ('freturn','areturn','ireturn'):
                return stack.pop()
            else:raise ValueError('null経路の未知の命令です: '+op+' '+comment)
        raise ValueError('null経路が終了しません')

    def null_direction(self, suffix):
        owner=self.name(suffix)
        _,methods=self.read(owner)
        candidates=[codes[0] for desc,codes in methods.items() if desc.endswith(')F') and len(re.findall(r'L[^;]+;',desc.split(')')[0]))==3 and desc.endswith(';I)F')] or [codes[0] for desc,codes in methods.items() if desc.endswith(')F') and len(re.findall(r'L[^;]+;',desc.split(')')[0]))==3]
        if len(candidates)!=1:raise ValueError('針propertyメソッドが一意でありません: '+owner)
        self.trace=[]
        value=self.evaluate(owner,candidates[0],{0:'property',1:'default-stack',2:None,3:None,4:0})
        if value!=0:raise ValueError('world/entityなしの針が0を返しません')
        return {'class':owner,'direction':value,'nullTrace':self.trace.copy(),'randomReached':False}

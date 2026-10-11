package io.github.rs0325.mcicon.worker;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Resolves official source names against an unmodified, obfuscated client. */
public final class Symbols {
    private final Map<String,String> sourceClasses = new HashMap<String,String>();
    private final Map<String,String> classes = new HashMap<String,String>();
    private final Map<String,String> fields = new HashMap<String,String>();
    private final Map<String,List<Member>> methods = new HashMap<String,List<Member>>();
    private static final Pattern METHOD = Pattern.compile("(?:\\d+:\\d+:)?([^ ]+) ([^(]+)\\(([^)]*)\\)(?::\\d+(?::\\d+)?)? -> (.+)");
    private static final class Member {
        final String actual; final String[] parameters;
        Member(String actual, String[] parameters) { this.actual=actual; this.parameters=parameters; }
    }
    public Symbols(Path file) throws IOException {
        if (file == null) return;
        String owner = null;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.startsWith("#") || line.trim().isEmpty()) continue;
            if (!line.startsWith(" ")) {
                int arrow=line.indexOf(" -> ");
                owner=line.substring(0,arrow);
                String actual=line.substring(arrow+4,line.length()-1);
                classes.put(owner,actual);sourceClasses.put(actual,owner);
            } else {
                Matcher match=METHOD.matcher(line.trim());
                if (match.matches()) {
                    String key=owner+"#"+match.group(2);
                    List<Member> list=methods.get(key);
                    if (list == null) { list=new ArrayList<Member>(); methods.put(key,list); }
                    list.add(new Member(match.group(4),match.group(3).isEmpty()?new String[0]:match.group(3).split(",")));
                } else {
                    String[] pair=line.trim().split(" -> ");
                    if (pair.length==2) fields.put(owner+"#"+pair[0].substring(pair[0].indexOf(' ')+1),pair[1]);
                }
            }
        }
    }
    public String sourceClass(String actual) {return sourceClasses.containsKey(actual)?sourceClasses.get(actual):actual;}
    public boolean hasClass(String name) {return classes.containsKey(name);}
    public boolean hasField(String owner,String name) {return fields.containsKey(owner+"#"+name);}
    public boolean hasMethod(String owner,String name,String...parameters) {
        List<Member> list=methods.get(owner+"#"+name);
        if(list!=null)for(Member member:list)if(Arrays.equals(member.parameters,parameters))return true;
        return false;
    }
    public boolean hasMethod(String owner,String name,int arity) {
        List<Member> list=methods.get(owner+"#"+name);
        if(list!=null)for(Member member:list)if(member.parameters.length==arity)return true;
        return false;
    }
    public String actualClass(String name) { return classes.containsKey(name)?classes.get(name):name; }
    public Class<?> type(String name) throws ClassNotFoundException { return Class.forName(actualClass(name)); }
    public String fieldName(String owner,String name) { String value=fields.get(owner+"#"+name); return value==null?name:value; }
    public Object field(String owner,String name,Object object) throws Exception {
        Field f=type(owner).getDeclaredField(fieldName(owner,name)); f.setAccessible(true); return f.get(object);
    }
    public void setField(String owner,String name,Object object,Object value) throws Exception {
        Field f=type(owner).getDeclaredField(fieldName(owner,name)); f.setAccessible(true); f.set(object,value);
    }
    public String methodName(String owner,String name,String...parameters) {
        List<Member> list=methods.get(owner+"#"+name);
        if(list!=null)for(Member member:list)if(Arrays.equals(member.parameters,parameters))return member.actual;
        return name;
    }
    public String methodName(String owner,String name,int arity) {
        List<Member> list=methods.get(owner+"#"+name);
        if (list!=null) for (Member m:list) if(m.parameters.length==arity) return m.actual;
        return name;
    }
    public Object call(String owner,String name,Object target,Object... args) throws Exception {
        List<Member> mapped=methods.get(owner+"#"+name);
        if (mapped!=null) {
            for(Member member:mapped) {
                if(member.parameters.length!=args.length) continue;
                Class<?>[] parameters=new Class<?>[args.length];
                for(int i=0;i<args.length;i++) parameters[i]=parameterType(member.parameters[i]);
                if(!compatible(parameters,args)) continue;
                Method method=type(owner).getDeclaredMethod(member.actual,parameters);
                method.setAccessible(true);
                return invoke(method,target,args);
            }
        } else {
            for(Method method:type(owner).getMethods()) if(method.getName().equals(name)&&compatible(method.getParameterTypes(),args)) return invoke(method,target,args);
            for(Method method:type(owner).getDeclaredMethods()) if(method.getName().equals(name)&&compatible(method.getParameterTypes(),args)) {
                method.setAccessible(true); return invoke(method,target,args);
            }
        }
        throw new NoSuchMethodException(owner+"."+name+"/"+args.length);
    }
    private Object invoke(Method method,Object target,Object[] args) throws Exception {
        try { return method.invoke(target,args); }
        catch(InvocationTargetException e) {
            Throwable cause=e.getCause(); if(cause instanceof Exception) throw (Exception)cause;
            throw new RuntimeException(cause);
        }
    }
    public Object construct(String owner,Object...args) throws Exception {
        for(Constructor<?> c:type(owner).getDeclaredConstructors()) if(compatible(c.getParameterTypes(),args)) {
            c.setAccessible(true); return c.newInstance(args);
        }
        throw new NoSuchMethodException(owner+" constructor/"+args.length);
    }
    private Class<?> parameterType(String name) throws Exception {
        if(name.endsWith("[]")) return Array.newInstance(parameterType(name.substring(0,name.length()-2)),0).getClass();
        if(name.equals("boolean")) return boolean.class;
        if(name.equals("int")) return int.class;
        if(name.equals("long")) return long.class;
        if(name.equals("float")) return float.class;
        if(name.equals("double")) return double.class;
        if(name.equals("byte")) return byte.class;
        if(name.equals("short")) return short.class;
        if(name.equals("char")) return char.class;
        return type(name);
    }
    private boolean compatible(Class<?>[] types,Object[] args) {
        if(types.length!=args.length) return false;
        for(int i=0;i<types.length;i++) {
            if(args[i]==null) { if(types[i].isPrimitive()) return false; continue; }
            Class<?> t=types[i];
            if(t.isPrimitive()) {
                if(t==int.class)t=Integer.class; else if(t==boolean.class)t=Boolean.class;
                else if(t==float.class)t=Float.class; else if(t==double.class)t=Double.class;
                else if(t==long.class)t=Long.class; else if(t==byte.class)t=Byte.class;
                else if(t==short.class)t=Short.class; else if(t==char.class)t=Character.class;
            }
            if(!t.isInstance(args[i])) return false;
        }
        return true;
    }
}

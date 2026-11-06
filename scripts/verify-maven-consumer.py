#!/usr/bin/env python3
"""空のGradle cacheを持つ独立consumerで正式固定依存、raw、workerとruntime resourceを検証する。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import zipfile


def compare_artifacts(bundle, version, artifacts):
    results = []
    with zipfile.ZipFile(bundle) as archive:
        for artifact in ('mcicon-core', 'mcicon-worker'):
            name = f'{artifact}-{version}.jar'
            entry = f'io/github/rs0325/mcicon/{artifact}/{version}/{name}'
            expected = hashlib.sha256(archive.read(entry)).hexdigest()
            actual = hashlib.sha256(Path(artifacts[artifact]).read_bytes()).hexdigest()
            results.append({'artifact': name, 'candidateSha256': expected, 'resolvedSha256': actual,
                            'passed': expected == actual})
    return results


def managed_runtime_missing(root):
    required = ['metadata/version_manifest_v2.json', 'versions/1.13/version.json',
                'versions/1.13/client.jar',
                'mappings/1.13/feather.jar', 'mappings/1.13/intermediary.jar']
    missing = [item for item in required if not (root / item).is_file()]
    metadata_path = root / 'versions/1.13/version.json'
    if metadata_path.is_file():
        try:
            metadata = json.loads(metadata_path.read_text(encoding='utf-8'))
            index_id = metadata.get('assetIndex', {}).get('id')
            if not index_id:
                missing.append('assetIndex.id')
            else:
                index_rel = f'assets/indexes/{index_id}.json'
                index_path = root / index_rel
                if not index_path.is_file():
                    missing.append(index_rel)
                else:
                    for key, obj in json.loads(index_path.read_text(encoding='utf-8')).get('objects', {}).items():
                        if key.endswith('.ogg'):
                            continue
                        digest = obj.get('hash', '')
                        if len(digest) != 40:
                            missing.append(f'assets/indexes/{index_id}.json: invalid asset hash for {key}')
                            continue
                        rel = f'assets/objects/{digest[:2]}/{digest}'
                        if not (root / rel).is_file():
                            missing.append(rel)
            for library in metadata.get('libraries', []):
                allowed = not library.get('rules')
                for rule in library.get('rules', []):
                    os_rule = rule.get('os', {})
                    if os_rule.get('name') not in (None, 'linux') or os_rule.get('arch') not in (None, 'amd64'):
                        continue
                    if rule.get('features'):
                        continue
                    allowed = rule.get('action') == 'allow'
                if not allowed:
                    continue
                downloads = library.get('downloads', {})
                items = [downloads.get('artifact')]
                native = library.get('natives', {}).get('linux')
                if native:
                    classifier = native.replace('${arch}', '64')
                    items.append(downloads.get('classifiers', {}).get(classifier))
                for item in items:
                    if item and item.get('path'):
                        rel = f"libraries/{item['path']}"
                        if not (root / rel).is_file():
                            missing.append(rel)
        except (OSError, json.JSONDecodeError, AttributeError):
            missing.append('version.json parse')
    return list(dict.fromkeys(missing))


def managed_api_result(runtime_supplied, markers, stage):
    if not runtime_supplied:
        return '未実施: --managed-runtime 未指定'
    doctor = markers.get('doctor', '未実施')
    if doctor == '未実施':
        doctor += f'（{stage}）'
    return f"managed API: doctorManaged={doctor}、renderManaged=未実施（指定runtimeは描画入力に接続されない）"


def managed_failure_stage(output, exit_code):
    if not exit_code:
        return '実行前'
    if re.search(r"(?m)^> Task :(?:compileJava|compileTestJava) FAILED\s*$", output):
        return 'Gradleコンパイル失敗'
    return 'Gradle実行失敗'


def managed_markers(output, nonce):
    events = set(re.findall(rf"(?m)^MCICON_MANAGED:{re.escape(nonce)}:(DOCTOR_START|DOCTOR_END)$", output))
    if 'DOCTOR_END' in events:
        return {'doctor': '呼出完了'}
    if 'DOCTOR_START' in events:
        return {'doctor': '実行・失敗'}
    return {'doctor': '未実施'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--repository', type=Path, help='未公開候補のローカルMaven repository。省略時はCentralだけを使う')
    parser.add_argument('--candidate-bundle', type=Path, help='実解決したcore/worker JARのbytesを照合する公開前候補ZIP')
    parser.add_argument('--managed-runtime', type=Path,
                        help="Prepopulated 1.13 cache root: manifest, metadata, client, mappings, assets, and Linux libraries")
    parser.add_argument('--gradle', type=Path, default=Path('./gradlew'))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    workspace = Path(tempfile.mkdtemp(prefix='consumer-', dir=output))
    missing_runtime = managed_runtime_missing(args.managed_runtime.resolve()) if args.managed_runtime else []
    project = workspace / 'project'
    project.mkdir()
    (project / 'settings.gradle').write_text("rootProject.name = 'independent-consumer'\n")
    repository = ''
    if args.repository:
        repository = "exclusiveContent {\n    forRepository { maven { url = uri(" + json.dumps(args.repository.resolve().as_uri()) + ") } }\n    filter { includeGroup 'io.github.rs0325.mcicon' }\n}\n"
    (project / 'build.gradle').write_text("""plugins { id 'application' }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
repositories {
""" + repository + """mavenCentral()
}
dependencies { implementation 'io.github.rs0325.mcicon:mcicon-core:""" + args.version + """' }
application { mainClass = 'Consumer' }
if (providers.gradleProperty('pomOnly').isPresent()) {
    repositories.withType(MavenArtifactRepository).configureEach { repo -> repo.metadataSources { sources -> sources.mavenPom(); sources.artifact(); sources.ignoreGradleMetadataRedirection() } }
}
tasks.named('run') {
    args layout.buildDirectory.dir('fixture').get().asFile.absolutePath
    if (providers.gradleProperty('managedRuntime').isPresent()) {
        args providers.gradleProperty('managedRuntime').get()
        args providers.gradleProperty('managedNonce').get()
    }
}
tasks.register('recordResolvedArtifacts') {
    doLast {
        def artifacts = configurations.runtimeClasspath.resolvedConfiguration.resolvedArtifacts.findAll {
            it.moduleVersion.id.group == 'io.github.rs0325.mcicon'
        }
        def expected = ['mcicon-core', 'mcicon-worker'] as Set
        if ((artifacts.collect { it.moduleVersion.id.name } as Set) != expected || artifacts.size() != expected.size() ||
                artifacts.any { it.name != it.moduleVersion.id.name ||
                    it.moduleVersion.id.version != project.property('consumerVersion') }) {
            throw new GradleException('core/workerの解決座標が固定依存と一致しません')
        }
        def paths = artifacts.collectEntries { [(it.moduleVersion.id.name): it.file.absolutePath] }
        layout.buildDirectory.file('resolved-artifacts.json').get().asFile.text = groovy.json.JsonOutput.toJson(paths)
    }
}
""", encoding='utf-8')
    sources = project / 'src/main/java'
    sources.mkdir(parents=True)
    (sources / 'Consumer.java').write_text(JAVA, encoding='utf-8')
    environment = dict(os.environ)
    results = []
    nonce = secrets.token_hex(16)
    for mode in ['module', 'pom']:
        environment['GRADLE_USER_HOME'] = str(workspace / (mode + '-gradle-home'))
        command = [str(args.gradle.resolve()), '-p', str(project), 'clean', 'run', 'recordResolvedArtifacts',
                   '--no-daemon', '-PconsumerVersion=' + args.version]
        if args.managed_runtime and not missing_runtime:
            command.append('-PmanagedRuntime=' + str(args.managed_runtime.resolve()))
            command.append('-PmanagedNonce=' + nonce)
        if mode == 'pom':
            command.append('-PpomOnly=true')
        result = subprocess.run(command, env=environment, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (workspace / (mode + '.log')).write_text(result.stdout, encoding='utf-8')
        passed = result.returncode == 0 and 'CONSUMER_OK' in result.stdout
        execution = managed_markers(result.stdout, nonce)
        comparisons = []
        if passed and args.candidate_bundle:
            artifacts = json.loads((project / 'build/resolved-artifacts.json').read_text(encoding='utf-8'))
            comparisons = compare_artifacts(args.candidate_bundle, args.version, artifacts)
            passed = all(item['passed'] for item in comparisons)
        results.append({'metadata': mode, 'artifactComparisons': comparisons, 'passed': passed,
                        'gradleExitCode': result.returncode, 'failureStage': managed_failure_stage(result.stdout, result.returncode),
                        'managedApiMarkers': execution})
        if result.returncode:
            print(result.stdout[-8000:])
            break
    report = {'version': args.version, 'source': 'local-candidate' if args.repository else 'mavenCentral',
              'candidateBundle': str(args.candidate_bundle.resolve()) if args.candidate_bundle else None,
              'workspace': str(workspace), 'initialGradleCacheEmpty': True, 'results': results,
              'managedRuntimeMissing': missing_runtime,
              'managedApi': ('未実施: runtime入力不足（' + ', '.join(missing_runtime) + ')' if missing_runtime else
                             managed_api_result(args.managed_runtime is not None,
                                                next((result['managedApiMarkers'] for result in results
                                                      if result['managedApiMarkers']['doctor'] != '未実施'),
                                                     results[-1]['managedApiMarkers'] if results else {'doctor': '未実施'}),
                                                results[-1]['failureStage'] if results else '実行前')),
              'passed': len(results) == 2 and all(r['passed'] for r in results) and not missing_runtime}
    (output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return not report['passed']


JAVA = r'''import io.github.rs0325.mcicon.*;
import io.github.rs0325.mcicon.internal.WorkerArtifact;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.jar.*;
import javax.imageio.ImageIO;

public class Consumer {
    static String hash(byte[] data,String algorithm) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
    }
    static void cache(Path path,byte[] bytes) throws Exception {
        Files.createDirectories(path.getParent());Files.write(path,bytes);
        Files.writeString(Path.of(path+".sha256"),hash(bytes,"SHA-256"));
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]),input=root.resolve("cache"),out=root.resolve("output");
        ByteArrayOutputStream png=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB),"png",png);
        ByteArrayOutputStream jar=new ByteArrayOutputStream();
        try(JarOutputStream archive=new JarOutputStream(jar)) {
            archive.putNextEntry(new JarEntry("assets/minecraft/textures/item/test.png"));
            archive.write(png.toByteArray());archive.closeEntry();
        }
        byte[] client=jar.toByteArray();
        String metadata="{\"downloads\":{\"client\":{\"url\":\"https://example.invalid/client.jar\",\"sha1\":\""+hash(client,"SHA-1")+"\",\"size\":"+client.length+"}}}";
        String manifest="{\"latest\":{\"release\":\"1.13\"},\"versions\":[{\"id\":\"1.13\",\"type\":\"release\",\"releaseTime\":\"2018-07-18T00:00:00Z\",\"url\":\"https://example.invalid/version.json\",\"sha1\":\""+hash(metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8),"SHA-1")+"\"}]}";
        cache(input.resolve("metadata/version_manifest_v2.json"),manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        cache(input.resolve("versions/1.13/version.json"),metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        cache(input.resolve("versions/1.13/client.jar"),client);
        try(MCIconGenerator generator=new MCIconGenerator(input,true,ProgressListener.NONE,new CancellationToken())) {
            var result=generator.extract(new ExtractRequest("1.13",List.of(),true,out,false,false)).join();
            if(!result.complete()||result.succeeded()!=1)throw new AssertionError("raw failed");
            Path target=out.resolve("java/1.13/raw/assets/minecraft/textures/item/test.png");
            if(!Arrays.equals(png.toByteArray(),Files.readAllBytes(target)))throw new AssertionError("raw bytes changed");
            if(Files.exists(input.resolve("workers")))throw new AssertionError("raw prepared worker");
        }
        var worker=WorkerArtifact.resolve(input,new CancellationToken());
        if(!worker.sha256().equals(hash(Files.readAllBytes(worker.agentJar()),"SHA-256")))throw new AssertionError("worker hash mismatch");
        Duration startup=Duration.ofSeconds(180),item=Duration.ofSeconds(30);
        new WorkerOptions(worker.agentJar(),"context","image",startup,item);
        new WorkerOptions(worker.agentJar(),"context","image",startup,item,"/opt/java");
        new WorkerOptions(worker.agentJar(),"context","image",startup,item,"/opt/java",3072,2048);
        MCIconGenerator.class.getMethod("render",RenderRequest.class,WorkerOptions.class);
        MCIconGenerator.class.getMethod("doctor",String.class,WorkerOptions.class);
        MCIconGenerator.class.getMethod("listEntries",String.class,WorkerOptions.class);
        var managedRoot=args.length>1?Path.of(args[1]):root.resolve("runtime");
        var managed=new ManagedOptions(managedRoot,Duration.ofSeconds(2),startup,item,3072,2048);
        MCIconGenerator.class.getMethod("renderManaged",RenderRequest.class);
        MCIconGenerator.class.getMethod("renderManaged",RenderRequest.class,ManagedOptions.class);
        MCIconGenerator.class.getMethod("doctorManaged",String.class);
        MCIconGenerator.class.getMethod("doctorManaged",String.class,ManagedOptions.class);
        MCIconGenerator.class.getMethod("listEntriesManaged",String.class);
        MCIconGenerator.class.getMethod("listEntriesManaged",String.class,ManagedOptions.class);
        try(MCIconGenerator generator=new MCIconGenerator(input,true,ProgressListener.NONE,new CancellationToken())) {
            if(args.length>1) {
                System.out.println("MCICON_MANAGED:"+args[2]+":DOCTOR_START");
                var diagnostic=generator.doctorManaged("1.13",managed).join();
                System.out.println("MCICON_MANAGED:"+args[2]+":DOCTOR_END");
                if(!diagnostic.healthy())throw new AssertionError("正式managed runtimeのdoctorに失敗: "+diagnostic);
            }
        }
        var loader=MCIconGenerator.class.getClassLoader();
        String prefix="META-INF/mcicon/runtime/";
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        try(var stream=loader.getResourceAsStream(prefix+"pins.json")) {
            if(stream==null)throw new AssertionError("正式coreのruntime pinsがありません");
            var pins=json.readTree(stream);
            if(pins.path("schemaVersion").asInt()!=1||pins.path("runtimes").size()!=2)throw new AssertionError("runtime pinsが不正です");
            for(var pin:pins.path("runtimes")) {
                String path=pin.path("descriptorPath").asText();
                try(var descriptor=loader.getResourceAsStream(prefix+path);
                    var signature=loader.getResourceAsStream(prefix+path+".sig");
                    var trust=loader.getResourceAsStream(prefix+pin.path("trustKeyPath").asText())) {
                    if(descriptor==null||signature==null||trust==null)throw new AssertionError("正式runtime resourceが不足しています");
                    if(!hash(descriptor.readAllBytes(),"SHA-256").equals(pin.path("descriptorSha256").asText())||
                            signature.readAllBytes().length!=64||trust.readAllBytes().length!=96)throw new AssertionError("正式runtime resourceのpin/size不一致");
                }
            }
        }
        System.out.println("CONSUMER_OK "+worker.sha256());
    }
}
'''

if __name__ == '__main__':
    raise SystemExit(main())

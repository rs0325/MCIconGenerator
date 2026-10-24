#!/usr/bin/env python3
"""空のGradle cacheを持つ独立consumerで固定依存、raw、worker展開を検証する。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--repository', type=Path, help='未公開候補のローカルMaven repository。省略時はCentralだけを使う')
    parser.add_argument('--candidate-bundle', type=Path, help='実解決したcore/worker JARのbytesを照合する公開前候補ZIP')
    parser.add_argument('--gradle', type=Path, default=Path('./gradlew'))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    workspace = Path(tempfile.mkdtemp(prefix='consumer-', dir=output))
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
tasks.named('run') { args layout.buildDirectory.dir('fixture').get().asFile.absolutePath }
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
    for mode in ['module', 'pom']:
        environment['GRADLE_USER_HOME'] = str(workspace / (mode + '-gradle-home'))
        command = [str(args.gradle.resolve()), '-p', str(project), 'clean', 'run', 'recordResolvedArtifacts',
                   '--no-daemon', '-PconsumerVersion=' + args.version]
        if mode == 'pom':
            command.append('-PpomOnly=true')
        result = subprocess.run(command, env=environment, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (workspace / (mode + '.log')).write_text(result.stdout, encoding='utf-8')
        passed = result.returncode == 0 and 'CONSUMER_OK' in result.stdout
        comparisons = []
        if passed and args.candidate_bundle:
            artifacts = json.loads((project / 'build/resolved-artifacts.json').read_text(encoding='utf-8'))
            comparisons = compare_artifacts(args.candidate_bundle, args.version, artifacts)
            passed = all(item['passed'] for item in comparisons)
        results.append({'metadata': mode, 'artifactComparisons': comparisons, 'passed': passed})
        if result.returncode:
            print(result.stdout[-8000:])
            break
    report = {'version': args.version, 'source': 'local-candidate' if args.repository else 'mavenCentral',
              'candidateBundle': str(args.candidate_bundle.resolve()) if args.candidate_bundle else None,
              'workspace': str(workspace), 'initialGradleCacheEmpty': True, 'results': results,
              'managedApi': '6署名と固定信頼設定不足の明示失敗を確認。実描画・OS実機受入は未実施',
              'passed': len(results) == 2 and all(r['passed'] for r in results)}
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
        var managed=new ManagedOptions(root.resolve("runtime"),Duration.ofSeconds(2),startup,item,3072,2048);
        MCIconGenerator.class.getMethod("renderManaged",RenderRequest.class);
        MCIconGenerator.class.getMethod("renderManaged",RenderRequest.class,ManagedOptions.class);
        MCIconGenerator.class.getMethod("doctorManaged",String.class);
        MCIconGenerator.class.getMethod("doctorManaged",String.class,ManagedOptions.class);
        MCIconGenerator.class.getMethod("listEntriesManaged",String.class);
        MCIconGenerator.class.getMethod("listEntriesManaged",String.class,ManagedOptions.class);
        try(MCIconGenerator generator=new MCIconGenerator(input,true,ProgressListener.NONE,new CancellationToken())) {
            try {generator.doctorManaged("1.13",managed).join();throw new AssertionError("未発行runtimeを成功扱いしました");}
            catch(java.util.concurrent.CompletionException failure) {
                if(!(failure.getCause() instanceof McIconException cause)||!cause.code().equals("MANAGED_RUNTIME_INVALID"))throw failure;
            }
        }
        System.out.println("CONSUMER_OK "+worker.sha256());
    }
}
'''

if __name__ == '__main__':
    raise SystemExit(main())

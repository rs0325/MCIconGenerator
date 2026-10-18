#!/usr/bin/env python3
"""空のGradle cacheを持つ独立consumerで固定依存、raw、worker展開を検証する。"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--repository', type=Path, help='未公開候補のローカルMaven repository。省略時はCentralだけを使う')
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
""", encoding='utf-8')
    sources = project / 'src/main/java'
    sources.mkdir(parents=True)
    (sources / 'Consumer.java').write_text(JAVA, encoding='utf-8')
    environment = dict(os.environ)
    results = []
    for mode in ['module', 'pom']:
        environment['GRADLE_USER_HOME'] = str(workspace / (mode + '-gradle-home'))
        command = [str(args.gradle.resolve()), '-p', str(project), 'clean', 'run', '--no-daemon']
        if mode == 'pom':
            command.append('-PpomOnly=true')
        result = subprocess.run(command, env=environment, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (workspace / (mode + '.log')).write_text(result.stdout, encoding='utf-8')
        results.append({'metadata': mode, 'passed': result.returncode == 0 and 'CONSUMER_OK' in result.stdout})
        if result.returncode:
            print(result.stdout[-8000:])
            break
    report = {'version': args.version, 'source': 'local-candidate' if args.repository else 'mavenCentral',
              'workspace': str(workspace), 'initialGradleCacheEmpty': True, 'results': results,
              'managedApi': '未実装の接続・実描画は#93後の結合受入で確認',
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
        System.out.println("CONSUMER_OK "+worker.sha256());
    }
}
'''

if __name__ == '__main__':
    raise SystemExit(main())

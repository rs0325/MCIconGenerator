package io.github.rs0325.mcicon.helper.macos;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

final class VmConfiguration {
    private VmConfiguration() {}
    static byte[] create(Path base,String baseHash,Path runtime,Path staging,Path offline,int memoryMiB,ObjectNode owner,List<String> debs) throws IOException {
        String ownerJson=MacFiles.JSON.writeValueAsString(owner);
        StringBuilder yaml=new StringBuilder("minimumLimaVersion: 2.2.1\nbase: []\nvmType: vz\narch: aarch64\ncpus: 4\nmemory: "+memoryMiB+"MiB\ndisk: 24GiB\nimages:\n- location: "+MacFiles.quoted(base.toString())+"\n  arch: aarch64\n  digest: sha256:"+baseHash+"\nvmOpts:\n  vz:\n    rosetta:\n      enabled: true\n      binfmt: true\nmountType: virtiofs\nmounts:\n");
        mount(yaml,staging,"/mcicon/jobs",true);mount(yaml,offline,"/mcicon/offline",false);mount(yaml,runtime,"/mcicon/runtime",false);
        yaml.append("containerd:\n  system: false\n  user: false\nssh:\n  loadDotSSHPubKeys: false\n  forwardAgent: false\n  forwardX11: false\npropagateProxyEnv: false\nupgradePackages: false\nportForwards:\n- guestSocket: /var/run/docker.sock\n  hostSocket: '{{.Dir}}/sock/docker.sock'\nprovision:\n- mode: dependency\n  skipDefaultDependencyResolution: true\n  script: |\n    #!/bin/sh\n    exit 0\n- mode: system\n  script: |\n    #!/bin/sh\n    set -eu\n    export DEBIAN_FRONTEND=noninteractive\n    mkdir -p /etc/docker\n    cat > /etc/docker/daemon.json <<'MCICON_DAEMON'\n    {\"labels\":[\"io.github.rs0325.mcicon.generation="+owner.path("generationOwner").asText()+"\"]}\n    MCICON_DAEMON\n    cat > /var/lib/mcicon-owner.json <<'MCICON_OWNER'\n    "+ownerJson+"\n    MCICON_OWNER\n    dpkg -i");
        for(String deb:debs){if(!deb.matches("debs/[A-Za-z0-9._+~:-]+[.]deb"))throw new IOException("offline deb名が不正です");yaml.append(" /mcicon/offline/").append(deb);}
        yaml.append("\n    systemctl enable --now containerd docker\nprobes:\n- script: |\n    #!/bin/sh\n    set -eu\n    sudo docker info >/dev/null\n");return yaml.toString().getBytes(StandardCharsets.UTF_8);
    }
    static ObjectNode guestMarker(String owner,String digest) {ObjectNode value=MacFiles.JSON.createObjectNode();value.put("schemaVersion",1);value.put("generationOwner",owner);value.put("descriptorSha256",digest);return value;}
    private static void mount(StringBuilder yaml,Path path,String target,boolean writable) throws IOException {yaml.append("- location: ").append(MacFiles.quoted(path.toString())).append("\n  mountPoint: ").append(MacFiles.quoted(target)).append("\n  writable: ").append(writable).append('\n');}
}

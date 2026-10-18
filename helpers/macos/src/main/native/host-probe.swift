import Foundation
import Virtualization
import Darwin
import IOKit
import CryptoKit

func hostBinding(salt: String, platform: String) -> String? {
    guard let root = UUID(uuidString: salt), root.uuidString.lowercased() == salt,
          let machine = UUID(uuidString: platform) else { return nil }
    let bytes = Data(("mcicon.macos.host-binding.v1\0" + salt + "\0" + machine.uuidString.lowercased()).utf8)
    return SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
}

func ownedExecutableRunning(path: String) -> Bool? {
    let size = proc_listpids(UInt32(PROC_UID_ONLY), getuid(), nil, 0)
    if size <= 0 || size > 4 * 50000 { return nil }
    var pids = [Int32](repeating: 0, count: Int(size) / MemoryLayout<Int32>.stride + 64)
    let capacity = pids.count * MemoryLayout<Int32>.stride
    let count = pids.withUnsafeMutableBytes { proc_listpids(UInt32(PROC_UID_ONLY), getuid(), $0.baseAddress, Int32(capacity)) }
    if count <= 0 || count >= capacity || count % 4 != 0 { return nil }
    for pid in pids.prefix(Int(count) / MemoryLayout<Int32>.stride) where pid > 0 {
        var name = [CChar](repeating: 0, count: 4 * Int(MAXPATHLEN))
        errno = 0
        let length = name.withUnsafeMutableBytes { proc_pidpath(pid, $0.baseAddress, UInt32($0.count)) }
        if length <= 0 {
            if errno == ESRCH { continue }
            return nil
        }
        if String(cString: name) == path { return true }
    }
    return false
}

let arguments = CommandLine.arguments
if arguments.count == 3 && arguments[1] == "--owned-executable" {
    let path = arguments[2]
    guard path.hasPrefix("/"), URL(fileURLWithPath: path).resolvingSymlinksInPath().path == path,
          let matched = ownedExecutableRunning(path: path) else { exit(1) }
    let bytes = try JSONSerialization.data(withJSONObject: ["complete": true, "matched": matched], options: [.sortedKeys])
    FileHandle.standardOutput.write(bytes)
    exit(0)
}
if arguments.count != 3 || arguments[1] != "--host-salt" { exit(1) }
let service = IOServiceGetMatchingService(kIOMainPortDefault, IOServiceMatching("IOPlatformExpertDevice"))
if service == IO_OBJECT_NULL { exit(1) }
let platform = IORegistryEntryCreateCFProperty(service, "IOPlatformUUID" as CFString, kCFAllocatorDefault, 0)?.takeRetainedValue() as? String
IOObjectRelease(service)
guard let platform = platform, let binding = hostBinding(salt: arguments[2], platform: platform) else { exit(1) }

// 導入APIを呼ばず、現在のVZ/Rosetta能力だけを返す。
let supported: Bool
let rosetta: String
if #available(macOS 13.0, *) {
    supported = VZVirtualMachine.isSupported
    switch VZLinuxRosettaDirectoryShare.availability {
    case .installed: rosetta = "installed"
    case .notInstalled: rosetta = "not-installed"
    case .notSupported: rosetta = "not-supported"
    @unknown default: rosetta = "unknown"
    }
} else {
    supported = false
    rosetta = "not-supported"
}
#if arch(arm64)
let arch = "aarch64"
#else
let arch = "unsupported"
#endif
var bootSize = 0
if sysctlbyname("kern.bootsessionuuid", nil, &bootSize, nil, 0) != 0 { exit(1) }
var bootBytes = [CChar](repeating: 0, count: bootSize)
let bootResult = bootBytes.withUnsafeMutableBytes { sysctlbyname("kern.bootsessionuuid", $0.baseAddress, &bootSize, nil, 0) }
if bootResult != 0 { exit(1) }
let boot = String(cString: bootBytes).lowercased()
guard let bootId = UUID(uuidString: boot), bootId.uuidString.lowercased() == boot else { exit(1) }
let version = ProcessInfo.processInfo.operatingSystemVersion
let value: [String: Any] = ["vz": supported, "rosetta": rosetta, "arch": arch,
                           "osVersion": "\(version.majorVersion).\(version.minorVersion).\(version.patchVersion)",
                           "uid": getuid(), "bootSessionId": boot, "hostBindingSha256": binding,
                           "physicalMemory": ProcessInfo.processInfo.physicalMemory,
                           "cpus": ProcessInfo.processInfo.processorCount]
let bytes = try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
FileHandle.standardOutput.write(bytes)

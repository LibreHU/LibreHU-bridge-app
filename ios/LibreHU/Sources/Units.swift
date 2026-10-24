import Foundation
import Security

/// A paired head unit: how to reach it and the access key it gave this phone (kept in the keychain).
struct HeadUnit: Codable, Identifiable, Hashable {
    var fingerprint: String
    var name: String
    var deviceId: String
    var ips: [String]
    var port: Int
    var id: String { fingerprint }
}

/// What a pairing QR code says: `librehu://pair?v=1&fp=…&t=…&n=…&ip=…&p=…&bt=…` (LibreHU-service, docs/bridge.md).
struct PairingCode {
    let fingerprint: String
    let token: Data
    let name: String
    let ips: [String]
    let port: Int

    static func parse(_ text: String) -> PairingCode? {
        guard let u = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)),
              u.scheme == "librehu", u.host == "pair" else { return nil }
        func q(_ k: String) -> String? { u.queryItems?.first { $0.name == k }?.value }
        guard q("v") == "1", let fp = q("fp"), let t = q("t"), let token = try? BridgeCrypto.unb64(t) else { return nil }
        return PairingCode(
            fingerprint: fp,
            token: token,
            name: q("n") ?? "LibreHU",
            ips: (q("ip") ?? "").split(separator: ",").map(String.init).filter { !$0.isEmpty },
            port: Int(q("p") ?? "") ?? 47800
        )
    }
}

/// Paired head units: their description in the app's settings, their access keys in the keychain (this device only,
/// after the first unlock).
@MainActor
final class Units: ObservableObject {
    static let shared = Units()
    @Published private(set) var list: [HeadUnit] = []
    private let key = "units"

    private init() {
        if let d = UserDefaults.standard.data(forKey: key), let l = try? JSONDecoder().decode([HeadUnit].self, from: d) { list = l }
    }

    func save(_ u: HeadUnit, credential: Data? = nil) {
        list = list.filter { $0.fingerprint != u.fingerprint } + [u]
        persist()
        if let credential { Keychain.set(credential, account: u.fingerprint) }
    }

    func remove(_ fingerprint: String) {
        list = list.filter { $0.fingerprint != fingerprint }
        persist()
        Keychain.delete(account: fingerprint)
    }

    func credential(_ fingerprint: String) -> Data? { Keychain.get(account: fingerprint) }

    private func persist() {
        if let d = try? JSONEncoder().encode(list) { UserDefaults.standard.set(d, forKey: key) }
    }
}

enum Keychain {
    private static let service = "org.librehu.bridge.credential"

    static func set(_ data: Data, account: String) {
        delete(account: account)
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData as String: data,
        ]
        SecItemAdd(q as CFDictionary, nil)
    }

    static func get(account: String) -> Data? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        return SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess ? out as? Data : nil
    }

    static func delete(account: String) {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
        SecItemDelete(q as CFDictionary)
    }
}

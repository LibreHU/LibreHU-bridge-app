import CryptoKit
import Foundation

/// Errors of the link, with a message for the screen.
struct BridgeError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// LibreHU bridge protocol, version 1, as in the Android app and LibreHU-service (`protocol/BridgeChannel.kt`):
/// frames of a 4-byte big-endian length and UTF-8 JSON; P-256 ephemeral keys and nonces, the head unit signs the
/// transcript with its static key (ECDSA-SHA256, checked against the fingerprint of the QR code), keys derived with
/// HKDF-SHA256 (salt = transcript), then AES-256-GCM frames, one key per direction, nonce = direction + counter.
/// Keys travel as base64url of their X.509 SubjectPublicKeyInfo (DER).
enum BridgeCrypto {
    static let version = 1
    static let label = "LibreHU-bridge-1"

    static func b64(_ d: Data) -> String {
        d.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    static func unb64(_ s: String) throws -> Data {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        guard let d = Data(base64Encoded: t) else { throw BridgeError("bad base64") }
        return d
    }

    static func sha256(_ parts: Data...) -> Data {
        var h = SHA256()
        for p in parts { h.update(data: p) }
        return Data(h.finalize())
    }

    /// What the QR code and the phone keep of the head unit's static key.
    static func fingerprint(_ spki: Data) -> String { b64(sha256(spki)) }

    static func hmac(key: Data, data: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: key)))
    }

    static func random(_ n: Int) -> Data {
        var d = Data(count: n)
        let ok = d.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, n, $0.baseAddress!) }
        precondition(ok == errSecSuccess)
        return d
    }
}

/// A byte stream the channel runs over (TCP here).
protocol ByteStream: AnyObject {
    func read(_ n: Int) async throws -> Data
    func write(_ d: Data) async throws
    func close()
}

/// The encrypted channel once the handshake is done: `send` / `receive` JSON objects.
final class BridgeChannel {
    static let maxFrame = 4 * 1024 * 1024
    private static let tagClient: UInt32 = 0x5048_4F4E // "PHON"
    private static let tagServer: UInt32 = 0x554E_4954 // "UNIT"

    private let stream: ByteStream
    private let sendKey: SymmetricKey
    private let receiveKey: SymmetricKey
    private var sendCounter: UInt64 = 0
    private var receiveCounter: UInt64 = 0
    private let sendLock = NSLock()
    /// Transcript hash of the handshake (authentication proofs).
    let transcript: Data
    /// The head unit's static key fingerprint, as checked.
    let serverFingerprint: String

    private init(stream: ByteStream, sendKey: Data, receiveKey: Data, transcript: Data, fingerprint: String) {
        self.stream = stream
        self.sendKey = SymmetricKey(data: sendKey)
        self.receiveKey = SymmetricKey(data: receiveKey)
        self.transcript = transcript
        serverFingerprint = fingerprint
    }

    private static func nonce(_ tag: UInt32, _ counter: UInt64) -> Data {
        var n = Data()
        withUnsafeBytes(of: tag.bigEndian) { n.append(contentsOf: $0) }
        withUnsafeBytes(of: counter.bigEndian) { n.append(contentsOf: $0) }
        return n
    }

    static func writeFrame(_ s: ByteStream, _ d: Data) async throws {
        var out = Data()
        withUnsafeBytes(of: UInt32(d.count).bigEndian) { out.append(contentsOf: $0) }
        out.append(d)
        try await s.write(out)
    }

    static func readFrame(_ s: ByteStream) async throws -> Data {
        let head = try await s.read(4)
        let n = head.reduce(0) { ($0 << 8) | Int($1) }
        if n < 0 || n > maxFrame { throw BridgeError("frame of \(n) bytes") }
        return n == 0 ? Data() : try await s.read(n)
    }

    static func parse(_ d: Data) throws -> [String: Any] {
        guard let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { throw BridgeError("not a JSON object") }
        return o
    }

    func send(_ message: [String: Any]) async throws {
        let plain = try JSONSerialization.data(withJSONObject: message)
        sendLock.lock()
        let counter = sendCounter
        sendCounter += 1
        sendLock.unlock()
        let box = try AES.GCM.seal(plain, using: sendKey, nonce: AES.GCM.Nonce(data: Self.nonce(Self.tagClient, counter)))
        try await Self.writeFrame(stream, box.ciphertext + box.tag)
    }

    func receive() async throws -> [String: Any] {
        let frame = try await Self.readFrame(stream)
        guard frame.count >= 16 else { throw BridgeError("frame not authentic") }
        let counter = receiveCounter
        receiveCounter += 1
        do {
            let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: Self.nonce(Self.tagServer, counter)), ciphertext: frame.prefix(frame.count - 16), tag: frame.suffix(16))
            return try Self.parse(try AES.GCM.open(box, using: receiveKey))
        } catch is BridgeError {
            throw BridgeError("not a JSON object")
        } catch {
            throw BridgeError("frame not authentic")
        }
    }

    func close() { stream.close() }

    /// Phone side: handshake with the head unit; `pinned` = the fingerprint from the QR code or kept at pairing.
    static func connect(_ stream: ByteStream, pinned: String?) async throws -> BridgeChannel {
        let eph = P256.KeyAgreement.PrivateKey()
        let nonce = BridgeCrypto.random(32)
        let hello: [String: Any] = ["v": BridgeCrypto.version, "e": BridgeCrypto.b64(eph.publicKey.derRepresentation), "n": BridgeCrypto.b64(nonce)]
        try await writeFrame(stream, try JSONSerialization.data(withJSONObject: hello))
        let reply = try parse(try await readFrame(stream))
        func str(_ k: String) throws -> String {
            guard let v = reply[k] as? String else { throw BridgeError("missing \(k)") }
            return v
        }
        let serverEph = try BridgeCrypto.unb64(try str("e"))
        let serverNonce = try BridgeCrypto.unb64(try str("n"))
        let serverStatic = try BridgeCrypto.unb64(try str("s"))
        let fp = BridgeCrypto.fingerprint(serverStatic)
        if let pinned, pinned != fp { throw BridgeError(String(localized: "error_not_paired_unit")) }
        let th = BridgeCrypto.sha256(Data(BridgeCrypto.label.utf8), eph.publicKey.derRepresentation, nonce, serverEph, serverNonce, serverStatic)
        let staticKey = try P256.Signing.PublicKey(derRepresentation: serverStatic)
        let sig = try P256.Signing.ECDSASignature(derRepresentation: try BridgeCrypto.unb64(try str("sig")))
        guard staticKey.isValidSignature(sig, for: th) else { throw BridgeError("bad signature") }
        let shared = try eph.sharedSecretFromKeyAgreement(with: P256.KeyAgreement.PublicKey(derRepresentation: serverEph))
        let ikm = shared.withUnsafeBytes { Data($0) }
        let keys = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm), salt: th, info: Data("LibreHU bridge keys".utf8), outputByteCount: 64)
        let k = keys.withUnsafeBytes { Data($0) }
        return BridgeChannel(stream: stream, sendKey: k.prefix(32), receiveKey: k.suffix(32), transcript: th, fingerprint: fp)
    }
}

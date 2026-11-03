import AVFoundation
import CoreMedia
import SwiftUI
import UIKit

/// H.264 of the head unit's screen (binary frames: kind `C` SPS and PPS, `K` key frame, `P` other frame, the pts on
/// 8 bytes in µs, then Annex B data) shown by an AVSampleBufferDisplayLayer, which decodes it in hardware: the NAL
/// units after their start codes become length-prefixed (AVCC) samples, each one displayed at once.
final class ScreenRenderer {
    private let layer: AVSampleBufferDisplayLayer
    private let queue = DispatchQueue(label: "librehu-screen")
    private let onBroken: () -> Void
    // On `queue`.
    private var format: CMVideoFormatDescription?
    private var waitKey = true

    init(layer: AVSampleBufferDisplayLayer, onBroken: @escaping () -> Void) {
        self.layer = layer
        self.onBroken = onBroken
    }

    func feed(_ frame: Data) {
        queue.async { [weak self] in self?.handle([UInt8](frame)) }
    }

    private func handle(_ b: [UInt8]) {
        guard b.count >= 9 else { return }
        let kind = b[0]
        var sps: ArraySlice<UInt8>?
        var pps: ArraySlice<UInt8>?
        var units: [ArraySlice<UInt8>] = []
        for n in Self.nalUnits(b, from: 9) {
            switch n.first! & 0x1F {
            case 7: sps = n
            case 8: pps = n
            default: units.append(n)
            }
        }
        if let sps, let pps, let d = Self.description(sps, pps) { format = d }
        if kind == UInt8(ascii: "K") { waitKey = false }
        guard !waitKey, !units.isEmpty, let format, let sample = Self.sample(units, format) else { return }
        DispatchQueue.main.async { [weak self, layer] in
            if layer.status == .failed {
                // Decoder lost (the app was in the background…): again from the next key frame, asked at once.
                layer.flush()
                self?.queue.async { self?.waitKey = true }
                self?.onBroken()
                return
            }
            layer.enqueue(sample)
        }
    }

    /// NAL units of an Annex B buffer (start codes 00 00 01 or 00 00 00 01).
    static func nalUnits(_ b: [UInt8], from start: Int) -> [ArraySlice<UInt8>] {
        var out: [ArraySlice<UInt8>] = []
        var begin = -1
        var i = start
        func close(_ end: Int) {
            var e = end
            while e > begin, b[e - 1] == 0 { e -= 1 }
            if e > begin { out.append(b[begin ..< e]) }
        }
        while i + 2 < b.count {
            if b[i] == 0, b[i + 1] == 0, b[i + 2] == 1 {
                if begin >= 0 { close(i) }
                i += 3
                begin = i
                continue
            }
            i += 1
        }
        if begin >= 0 { close(b.count) }
        return out
    }

    static func description(_ sps: ArraySlice<UInt8>, _ pps: ArraySlice<UInt8>) -> CMVideoFormatDescription? {
        let s = Array(sps)
        let p = Array(pps)
        var desc: CMFormatDescription?
        let status = s.withUnsafeBufferPointer { sb in
            p.withUnsafeBufferPointer { pb in
                let pointers = [sb.baseAddress!, pb.baseAddress!]
                let sizes = [s.count, p.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault, parameterSetCount: 2, parameterSetPointers: pointers,
                    parameterSetSizes: sizes, nalUnitHeaderLength: 4, formatDescriptionOut: &desc
                )
            }
        }
        return status == noErr ? desc : nil
    }

    static func sample(_ units: [ArraySlice<UInt8>], _ format: CMVideoFormatDescription) -> CMSampleBuffer? {
        var avcc: [UInt8] = []
        avcc.reserveCapacity(units.reduce(0) { $0 + $1.count + 4 })
        for u in units {
            withUnsafeBytes(of: UInt32(u.count).bigEndian) { avcc.append(contentsOf: $0) }
            avcc.append(contentsOf: u)
        }
        var block: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: avcc.count, blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil, offsetToData: 0, dataLength: avcc.count, flags: 0, blockBufferOut: &block
        ) == kCMBlockBufferNoErr, let block else { return nil }
        let copied = avcc.withUnsafeBytes { CMBlockBufferReplaceDataBytes(with: $0.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: avcc.count) }
        guard copied == kCMBlockBufferNoErr else { return nil }
        var sample: CMSampleBuffer?
        var size = avcc.count
        guard CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format, sampleCount: 1,
            sampleTimingEntryCount: 0, sampleTimingArray: nil, sampleSizeEntryCount: 1, sampleSizeArray: &size,
            sampleBufferOut: &sample
        ) == noErr, let sample else { return nil }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true), CFArrayGetCount(attachments) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dict, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(), Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        return sample
    }
}

/// The video's view: its layer shows the frames, each finger goes to the head unit (`screen.touch`: `d` down, `m`
/// moved, `u` up, `c` cancelled; id, x, y of each finger, x and y from 0 to 1 of the screen).
final class ScreenLayerView: UIView {
    override class var layerClass: AnyClass { AVSampleBufferDisplayLayer.self }
    var displayLayer: AVSampleBufferDisplayLayer { layer as! AVSampleBufferDisplayLayer }
    var onTouch: ((String, [Double]) -> Void)?
    private var ids: [ObjectIdentifier: Int] = [:]

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        backgroundColor = .black
        displayLayer.videoGravity = .resizeAspect
    }

    required init?(coder: NSCoder) { fatalError("not from a storyboard") }

    private func finger(_ t: UITouch) -> [Double] {
        let p = t.location(in: self)
        let x = min(max(Double(p.x / max(bounds.width, 1)), 0), 1)
        let y = min(max(Double(p.y / max(bounds.height, 1)), 0), 1)
        return [Double(ids[ObjectIdentifier(t)] ?? 0), x, y]
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches {
            let used = Set(ids.values)
            ids[ObjectIdentifier(t)] = (0 ..< 10).first { !used.contains($0) } ?? 9
            onTouch?("d", finger(t))
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        let down = (event?.allTouches ?? touches).filter { ids[ObjectIdentifier($0)] != nil }
        onTouch?("m", down.flatMap { finger($0) })
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches where ids[ObjectIdentifier(t)] != nil {
            onTouch?("u", finger(t))
            ids[ObjectIdentifier(t)] = nil
        }
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        ids.removeAll()
        onTouch?("c", [])
    }
}

/// One share of the head unit's screen: started while ScreenView is shown, the first frames (SPS / PPS, key frame,
/// which may arrive before the answer to `screen.start`) kept until the video's view exists.
@MainActor
final class ScreenSession: ObservableObject {
    @Published private(set) var size: CGSize?
    @Published private(set) var error: String?
    private let client = BridgeClient.shared
    private var renderer: ScreenRenderer?
    private var early: [Data] = []
    private var running = false

    func start() {
        guard !running else { return }
        running = true
        error = nil
        size = nil
        early = []
        client.screenFrame = { [weak self] d in self?.frame(d) }
        client.screenEvent = { [weak self] d in
            if d["on"] as? Bool == false { self?.ended(d["e"] as? String) }
        }
        Task {
            do {
                let r = try await client.call("screen.start", ["max": 1280], timeout: 30) as? [String: Any] ?? [:]
                let w = r["w"] as? Int ?? 0
                let h = r["h"] as? Int ?? 0
                guard w > 0, h > 0 else { throw BridgeError("screen size") }
                if running { size = CGSize(width: w, height: h) }
            } catch {
                if running { ended(error.localizedDescription) }
            }
        }
    }

    func stop() {
        guard running else { return }
        running = false
        client.post("screen.stop")
        clear()
    }

    func restart() {
        stop()
        // A share stopped on the head unit or failed: nothing to stop there, said again anyway.
        client.post("screen.stop")
        start()
    }

    func attach(_ v: ScreenLayerView) {
        let r = ScreenRenderer(layer: v.displayLayer) { [weak self] in
            Task { @MainActor in self?.client.post("screen.sync") }
        }
        v.onTouch = { [weak self] a, f in self?.client.post("screen.touch", ["a": a, "f": f]) }
        for d in early { r.feed(d) }
        early = []
        renderer = r
    }

    private func frame(_ d: Data) {
        if let renderer { renderer.feed(d) } else if early.count < 120 { early.append(d) }
    }

    private func ended(_ why: String?) {
        running = false
        clear()
        error = (why?.isEmpty == false) ? why : String(localized: "screen_ended")
    }

    private func clear() {
        client.screenFrame = nil
        client.screenEvent = nil
        renderer = nil
        early = []
        size = nil
    }
}

private struct ScreenSurface: UIViewRepresentable {
    let session: ScreenSession
    func makeUIView(context: Context) -> ScreenLayerView {
        let v = ScreenLayerView(frame: .zero)
        session.attach(v)
        return v
    }

    func updateUIView(_ v: ScreenLayerView, context: Context) {}
}

/// The head unit's screen on the iPhone, touched from here: for the passenger. LibreHU-service shares it over Wi-Fi
/// (root on the head unit); Back, Home and recent apps keys; the head unit keeps its own screen for the driver.
struct ScreenView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var phase
    @StateObject private var session = ScreenSession()
    private var client: BridgeClient { BridgeClient.shared }

    var body: some View {
        GeometryReader { g in
            let landscape = g.size.width > g.size.height
            let layout = landscape ? AnyLayout(HStackLayout(spacing: 0)) : AnyLayout(VStackLayout(spacing: 0))
            layout {
                ZStack {
                    Color.black
                    if let e = session.error {
                        VStack(spacing: 12) {
                            Text(e).foregroundStyle(.white).multilineTextAlignment(.center)
                            Button("retry") { session.restart() }.buttonStyle(.bordered).tint(.white)
                        }.padding()
                    } else if let s = session.size {
                        ScreenSurface(session: session).aspectRatio(s.width / s.height, contentMode: .fit)
                    } else {
                        ProgressView().tint(.white)
                    }
                }
                keys(landscape)
            }
        }
        .background(Color.black.ignoresSafeArea())
        .statusBarHidden()
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
            session.start()
        }
        .onDisappear {
            session.stop()
            UIApplication.shared.isIdleTimerDisabled = false
        }
        .onChange(of: phase) { p in
            if p == .active { session.start() } else if p == .background { session.stop() }
        }
    }

    @ViewBuilder private func keys(_ vertical: Bool) -> some View {
        let buttons = Group {
            key("chevron.backward", "screen_back") { client.post("screen.key", ["code": 4]) }
            key("house", "screen_home") { client.post("screen.key", ["code": 3]) }
            key("square.on.square", "screen_recents") { client.post("screen.key", ["code": 187]) }
            key("xmark", "screen_close") { dismiss() }
        }
        if vertical {
            VStack { buttons.frame(maxHeight: .infinity) }.frame(width: 64)
        } else {
            HStack { buttons.frame(maxWidth: .infinity) }.frame(height: 64)
        }
    }

    private func key(_ icon: String, _ label: LocalizedStringKey, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(label, systemImage: icon).labelStyle(.iconOnly).font(.title2).foregroundStyle(.white).frame(width: 56, height: 56)
        }
    }
}

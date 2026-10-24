import AVFoundation
import SwiftUI

@main
struct LibreHUApp: App {
    var body: some Scene {
        WindowGroup {
            HomeView().tint(Color(red: 0.54, green: 0.71, blue: 0.97))
        }
    }
}

/// Paired head units, pairing by QR code, and the steps for a first time.
struct HomeView: View {
    @StateObject private var units = Units.shared
    @StateObject private var client = BridgeClient.shared
    @State private var scanning = false
    @State private var busy = false
    @State private var error: String?
    @State private var path: [HeadUnit] = []
    @State private var forget: HeadUnit?

    var body: some View {
        NavigationStack(path: $path) {
            List {
                if units.list.isEmpty {
                    Section {
                        VStack(alignment: .leading, spacing: 12) {
                            Label("welcome_title", systemImage: "car.fill").font(.title3.bold())
                            Step(n: 1, text: "step_1")
                            Step(n: 2, text: "step_2")
                            Step(n: 3, text: "step_3")
                        }.padding(.vertical, 6)
                    }
                } else {
                    Section("your_units") {
                        ForEach(units.list) { u in
                            NavigationLink(value: u) {
                                HStack(spacing: 14) {
                                    Image(systemName: "car.fill").font(.title2).foregroundStyle(.tint)
                                    VStack(alignment: .leading) {
                                        Text(u.name).font(.headline)
                                        Text(u.ips.first ?? "").font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                            }
                            .swipeActions { Button(role: .destructive) { forget = u } label: { Label("forget", systemImage: "trash") } }
                        }
                    }
                }
                if case let .connecting(name) = client.state, busy {
                    Section { HStack { ProgressView(); Text(String(format: String(localized: "connecting"), name)) } }
                }
                if let error {
                    Section { Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red) }
                }
                Section(units.list.isEmpty ? "pair_title" : "pair_another") {
                    Button { scanning = true } label: {
                        Label { VStack(alignment: .leading) { Text("pair_qr"); Text("pair_qr_sub").font(.caption).foregroundStyle(.secondary) } } icon: { Image(systemName: "qrcode.viewfinder") }
                    }.disabled(busy)
                    Text("wifi_only").font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("LibreHU")
            .navigationDestination(for: HeadUnit.self) { UnitView(unit: $0) }
            .sheet(isPresented: $scanning) {
                QRScannerView { text in
                    scanning = false
                    guard let code = PairingCode.parse(text) else { error = String(localized: "qr_not_librehu"); return }
                    busy = true
                    error = nil
                    Task {
                        do {
                            let u = try await client.pair(code)
                            path = [u]
                        } catch {
                            self.error = String(format: String(localized: "pair_failed"), error.localizedDescription)
                        }
                        busy = false
                    }
                }
                .ignoresSafeArea()
            }
            .confirmationDialog(Text(String(format: String(localized: "forget_title"), forget?.name ?? "")), isPresented: Binding(get: { forget != nil }, set: { if !$0 { forget = nil } }), titleVisibility: .visible) {
                Button("forget", role: .destructive) {
                    if let f = forget { units.remove(f.fingerprint) }
                    forget = nil
                }
            } message: { Text("forget_text") }
        }
    }
}

private struct Step: View {
    let n: Int
    let text: LocalizedStringKey
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Text("\(n)").font(.callout.bold()).frame(width: 26, height: 26).background(Circle().fill(Color.accentColor.opacity(0.2)))
            Text(text).font(.callout)
        }
    }
}

/// The camera reading QR codes (AVFoundation); calls back once with the text.
struct QRScannerView: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerController {
        let c = ScannerController()
        c.onCode = onCode
        return c
    }

    func updateUIViewController(_ c: ScannerController, context: Context) {}

    final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
        var onCode: ((String) -> Void)?
        private let session = AVCaptureSession()
        private var sent = false

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            guard let device = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else { return }
            session.addInput(input)
            let output = AVCaptureMetadataOutput()
            guard session.canAddOutput(output) else { return }
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
            let layer = AVCaptureVideoPreviewLayer(session: session)
            layer.videoGravity = .resizeAspectFill
            layer.frame = view.bounds
            view.layer.addSublayer(layer)
            let hint = UILabel()
            hint.text = String(localized: "scan_prompt")
            hint.textColor = .white
            hint.textAlignment = .center
            hint.font = .preferredFont(forTextStyle: .headline)
            hint.frame = CGRect(x: 16, y: 60, width: view.bounds.width - 32, height: 40)
            hint.autoresizingMask = [.flexibleWidth]
            view.addSubview(hint)
            DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            view.layer.sublayers?.compactMap { $0 as? AVCaptureVideoPreviewLayer }.forEach { $0.frame = view.bounds }
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            session.stopRunning()
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
            guard !sent, let text = (objects.first as? AVMetadataMachineReadableCodeObject)?.stringValue else { return }
            sent = true
            onCode?(text)
        }
    }
}

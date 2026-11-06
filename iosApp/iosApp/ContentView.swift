import UIKit
import SwiftUI
import ComposeApp

struct BanditaComposeContainer: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        BanditaComposeContainer()
            .ignoresSafeArea(.all)
    }
}

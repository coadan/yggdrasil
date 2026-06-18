import Foundation
import SwiftUI

public final class PanelViewModel {
    public var title: String

    public init(title: String) {
        self.title = title
    }

    public func loadPanel(id: String) async {
        print(id)
    }
}

struct PanelView {
    let model: PanelViewModel
}

protocol PanelLoading {
    func loadPanel(id: String) async
}

actor PanelCache {
    var count: Int = 0
}

extension PanelViewModel {
    public func refresh() {
    }
}

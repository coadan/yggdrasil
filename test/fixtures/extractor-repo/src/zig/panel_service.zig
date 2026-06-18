const std = @import("std");
const client = @import("panel_client.zig");

pub const Panel = struct {
    id: []const u8,
};

pub fn loadPanel(id: []const u8) Panel {
    return Panel{ .id = id };
}

fn normalizeId(id: []const u8) []const u8 {
    return id;
}

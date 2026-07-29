const std = @import("std");
const ghostty_terminfo = @import("ghostty_terminfo");

pub fn main() !void {
    var buffer: [4096]u8 = undefined;
    var stdout = std.fs.File.stdout().writerStreaming(&buffer);
    try ghostty_terminfo.ghostty.encode(&stdout.interface);
    try stdout.end();
}

const std = @import("std");
const ghostty_terminfo = @import("ghostty_terminfo");

pub fn main(init: std.process.Init) !void {
    var buffer: [4096]u8 = undefined;
    var stdout = std.Io.File.stdout().writerStreaming(init.io, &buffer);
    try ghostty_terminfo.ghostty.encode(&stdout.interface);
    try stdout.end();
}

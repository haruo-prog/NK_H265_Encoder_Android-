# Bugfix: Low Resolution Encode Crash

Version 1.0.1 includes stability fixes for crashes that occurred after selecting a video, choosing low resolution, and starting H.265 encoding.

## Changes

- Use FFmpeg argument array execution instead of a single shell-like command string.
- Limit libx265 thread usage to reduce native memory pressure on Android devices.
- Add stronger Throwable handling around FFmpeg startup and completion callback.
- Reduce copy buffer size to lower memory pressure.
- Validate output file existence and size before saving.

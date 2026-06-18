package panels

import "core:fmt"
import json "core:encoding/json"

foreign import libc "system:c"

Panel :: struct {
    id: string,
}

Status :: enum {
    Ready,
}

Payload :: union {
    Panel,
}

Default_ID :: "panel-default"
active_count := 0

load_panel :: proc(id: string) -> Panel {
    fmt.println(id)
    return Panel{id = id}
}

normalize_id :: proc(id: string) -> string {
    return id
}

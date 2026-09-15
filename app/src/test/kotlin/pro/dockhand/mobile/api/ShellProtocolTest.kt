package pro.dockhand.mobile.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellProtocolTest {

    @Test
    fun inputAndResizeEncodeToProtocolJson() {
        assertEquals("""{"type":"input","data":"ls\n"}""", ShellProtocol.encodeInput("ls\n"))
        assertEquals("""{"type":"resize","cols":120,"rows":40}""", ShellProtocol.encodeResize(120, 40))
    }

    @Test
    fun serverMessagesDecodeToOutputErrorAndExit() {
        assertEquals(
            ShellServerMessage.Output("hello"),
            ShellProtocol.decodeServerMessage("""{"type":"output","data":"hello"}""")
        )
        assertEquals(
            ShellServerMessage.Error("boom"),
            ShellProtocol.decodeServerMessage("""{"type":"error","message":"boom"}""")
        )
        assertEquals(
            ShellServerMessage.Exit,
            ShellProtocol.decodeServerMessage("""{"type":"exit"}""")
        )
        assertEquals(
            ShellServerMessage.Error("Shell error"),
            ShellProtocol.decodeServerMessage("""{"type":"error"}""")
        )
        assertNull(ShellProtocol.decodeServerMessage("not json"))
        assertNull(ShellProtocol.decodeServerMessage("""{"type":"unknown"}"""))
        assertNull(ShellProtocol.decodeServerMessage("""{"type":"output"}"""))
    }
}

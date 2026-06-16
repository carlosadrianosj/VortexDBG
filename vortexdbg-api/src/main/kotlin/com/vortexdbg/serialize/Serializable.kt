package com.vortexdbg.serialize

import java.io.DataOutput
import java.io.IOException

interface Serializable {

    @Throws(IOException::class)
    fun serialize(out: DataOutput)

}

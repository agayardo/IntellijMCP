package ca.artemgm.developmentmcp.bridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun syntheticBridgeZip(): InputStream {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zos ->
        zos.putNextEntry(ZipEntry("stdio-mcp-server/"))
        zos.closeEntry()
        zos.putNextEntry(ZipEntry("stdio-mcp-server/bin/"))
        zos.closeEntry()
        zos.putNextEntry(ZipEntry("stdio-mcp-server/bin/stdio-mcp-server"))
        zos.write("#!/bin/sh\necho bridge".toByteArray())
        zos.closeEntry()
        zos.putNextEntry(ZipEntry("stdio-mcp-server/lib/"))
        zos.closeEntry()
        zos.putNextEntry(ZipEntry("stdio-mcp-server/lib/bridge.jar"))
        zos.write("fake-jar-content".toByteArray())
        zos.closeEntry()
    }
    return ByteArrayInputStream(baos.toByteArray())
}

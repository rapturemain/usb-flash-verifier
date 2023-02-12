package com.rapturemain.usbfv

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.InvalidPathException
import java.util.*
import kotlin.io.path.Path
import kotlin.math.min

private infix fun Int.pow(exp: Int): Int {
    var value = 1
    repeat(exp) { value *= this }
    return value
}

private val SIZE_SUFFIXES = mapOf(
    "KB" to (2 pow 10),
    "MB" to (2 pow 20),
    "GB" to (2 pow 30)
)

private val SIZE_SUFFIXES_REGEXP = run {
    val suffixesRegexp = SIZE_SUFFIXES.entries
        .map { it.key }
        .reduce { acc, s -> "$acc|$s"}
    Regex("[1-9]\\d*($suffixesRegexp)")
}

private fun printUsage() {
    println("""
            | Usage java -jar usb-flash-verifier.jar <path to drive's root folder> <size> <OPTIONAL: file name>
            | Example: java -jar usb-flash-verifier.jar "E:\" "4GB" "test-file"
            | Size should be an integer. Possible size suffixes: KB, MB, GB
            | Default file name: usb-flash-verifier-test-file.txt
        """.trimMargin())
}

fun main(args: Array<String>) {
    if (args.size !in 2..3) {
        printUsage()
        return
    }

    val path = try {
        Path(args[0])
    } catch (e: InvalidPathException) {
        printUsage()
        return
    }

    val size = try {
        val sizePart = args[1].substring(0, args[1].length - 2)
        val expPart = args[1].substring(args[1].length - 2, args[1].length)
        sizePart.toLong() * SIZE_SUFFIXES[expPart]!!
    } catch (e: Exception) {
        printUsage()
        return
    }

    val fileName = if (args.size >= 3) args[2] else "usb-flash-verifier-test-file.txt"

    val driveDirectory = try {
        path.toFile()
    } catch (e: Exception) {
        println("Cannot read directory $path")
        return
    }

    if (!driveDirectory.isDirectory) {
        printUsage()
        return
    }

    val bufferForFileAttributesEtc = (2 pow 20) * 16 // 16 MB
    if (driveDirectory.freeSpace < size + bufferForFileAttributesEtc) {
        println("Partition has only ${driveDirectory.freeSpace} bytes, but you're trying to test it with $size bytes")
        return
    }

    val testFile = File(driveDirectory.path + fileName)
    if (!testFile.createNewFile()) {
        println("Cannot create test file. Assuming we're validating now\n")
        println(validate(testFile).second)
        return
    }

    println("Generating a file with required size of $size bytes")
    val (successfulCreation, creationMessage) = createValidationFile(testFile, size)
    if (!successfulCreation) {
        println("Something went wrong during file creation")
        return
    }

    println(creationMessage)
    println("Now reinsert the flash drive and press any button...")
    println("Waiting...")
    readln()
    println("Starting validation")

    println(validate(testFile).second)
}

private fun validate(testFile: File): Pair<Boolean, String> {
    testFile.inputStream().use { input ->
        val metaBytes = ByteArray(Long.SIZE_BYTES * 2)
        val metaReadBytes = input.read(metaBytes, 0, metaBytes.size)
        if (metaReadBytes < Long.SIZE_BYTES * 2) {
            input.close()
            return false to "Corrupted file: cannot read RNG seed or size"
        }
        val (rngSeed, writtenFileSize) = with(ByteBuffer.wrap(metaBytes)) { long to long }

        val rng = Random(rngSeed)

        val chunkSize = 2 pow 24
        val readByteArray = ByteArray(chunkSize)
        val generatedByteArray = ByteArray(chunkSize)

        var lastReadBytes = 0
        var totalReadBytes = metaReadBytes.toLong()
        val read = {
            lastReadBytes = input.read(readByteArray, 0, readByteArray.size)
            lastReadBytes
        }

        while (read() > 0) {
            rng.nextBytes(generatedByteArray)

            if (lastReadBytes < chunkSize) {
                for (i in lastReadBytes until chunkSize) {
                    generatedByteArray[i] = 0
                    readByteArray[i] = 0
                }
            }

            if (!readByteArray.contentEquals(generatedByteArray)) {
                return false to "Content are not equals after $totalReadBytes byte"
            }

            totalReadBytes += lastReadBytes

            printPercentage(totalReadBytes, writtenFileSize)
        }

        if (totalReadBytes != writtenFileSize) {
            return false to "Expected to read $writtenFileSize but only read $totalReadBytes"
        }

        return true to "Validation for size $totalReadBytes bytes is successful"
    }
}

private fun createValidationFile(testFile: File, requiredSize: Long): Pair<Boolean, String> {
    testFile.outputStream().use { output ->
        val rngSeed = System.currentTimeMillis()
        val metaBytes = ByteArray(Long.SIZE_BYTES * 2).also {
            ByteBuffer.wrap(it).apply {
                putLong(rngSeed)
                putLong(requiredSize)
            }
        }
        output.write(metaBytes)

        val rng = Random(rngSeed)

        val chunkSize = 2 pow 24
        val writeByteArray = ByteArray(chunkSize)
        var totalWrittenBytes = metaBytes.size.toLong()

        while (totalWrittenBytes < requiredSize) {
            val leftBytesToWrite = requiredSize - totalWrittenBytes
            val bytesToWrite = min(leftBytesToWrite, chunkSize.toLong()).toInt()
            totalWrittenBytes += bytesToWrite

            rng.nextBytes(writeByteArray)
            output.write(writeByteArray, 0, bytesToWrite)

            printPercentage(totalWrittenBytes, requiredSize)
        }

        println("Generation completed... Waiting for the file flush by OS")
        return true to "Successfully written $totalWrittenBytes bytes"
    }
}

private fun printPercentage(done: Long, of: Long) {
    val percent = (done / of.toDouble()) * 100
    println("Done: ${percent.format(2)}%")
}

private fun Double.format(digits: Int) = "%.${digits}f".format(this)
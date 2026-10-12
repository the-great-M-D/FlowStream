package com.downloadx.libdex

import java.nio.file.Paths

/**
 * CLI validator: parse the recovered export files and print stats.
 * Exit code 0 = at least one config parsed from each provided source.
 *
 * Usage: Tool <stark_configs_extracted.json> <stark_serverlist.json> [dumpDir]
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("usage: Tool <raw-carve-export> <serverlist-export> [dumpDir]")
        kotlin.system.exitProcess(2)
    }
    val carve = ConfigStore.loadRawCarve(Paths.get(args[0]))
    println("raw carve export: ${carve.all.size} configs, ${carve.warnings.size} warnings")
    carve.warnings.take(3).forEach { println("  warn: $it") }

    if (args.size > 1) {
        val list = ConfigStore.loadServerList(Paths.get(args[1]))
        println("serverlist export: ${list.all.size} configs, ${list.named.count { it.name != null }} named, ${list.warnings.size} warnings")
        list.warnings.take(3).forEach { println("  warn: $it") }
    }

    if (args.size > 2) {
        val dump = ConfigStore.fromDumpDir(Paths.get(args[2]))
        println("dump dir: ${dump.all.size} configs, ${dump.warnings.size} warnings")
    }

    val modes = carve.all.groupingBy { it.connectionMode ?: -1 }.eachCount()
    println("connection modes: $modes")
    val hosts = carve.all.groupingBy { it.primaryHost ?: "?" }.eachCount()
    println("primary hosts: $hosts")

    val ok = carve.all.isNotEmpty()
    println(if (ok) "RESULT: OK" else "RESULT: EMPTY")
    kotlin.system.exitProcess(if (ok) 0 else 1)
}

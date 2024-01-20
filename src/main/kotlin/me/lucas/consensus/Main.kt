package me.lucas.consensus

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val directory = args.find { it.startsWith("--directory=") }?.substringAfter("=")
    val algorithm = args.find { it.startsWith("--algorithm=") }?.substringAfter("=")
    val nodes = args.find { it.startsWith("--nodes=") }?.substringAfter("=")?.toInt()
    val failures = args.find { it.startsWith("--failures=") }?.substringAfter("=")?.toInt()
    val guard: (Boolean, String) -> (Unit) = { invalid, message ->
        if (invalid) {
            println(message)
            exitProcess(1)
        }
    }
    val algorithms = arrayOf("raft", "rabia", "paxos", "pineapple", "pineapple-memory")

    guard(directory == null, "You must specify a directory with --directory=")
    guard(algorithm == null, "You must specify an algorithm with --algorithm= \nOptions - ${algorithms.joinToString(separator = ", ") { it }}")
    guard(algorithms.find { it.equals(algorithm, ignoreCase = false) } == null, "That is not an accepted algorithm \nOptions - ${algorithms.joinToString(separator = ", ") { it }}")
    guard(nodes == null, "You must specify an amount of nodes with --nodes=")
    nodes!!; directory!!

    if (algorithm == "rabia" || algorithm == "paxos") {
        guard(failures == null, "You must specify an amount of failures with --failures=")
        guard(failures!! >= nodes, "Failures must be less than $nodes nodes")
    }

    val file = File(directory)
    if (!file.exists()) file.mkdirs()
    val host = InetAddress.getLocalHost().hostName.split(".")[0]
    val addresses = (1..nodes).map { "10.10.1.$it" }
    val ip = addresses[0]
    println("Host: $host")
    println("IP: $ip")
    println("Algorithim: $algorithm")

    try {
        arrayOf(
                "git clone https://github.com/Exerosis/Raft.git",
                "git clone https://github.com/Exerosis/PineappleGo.git",
                "git clone https://github.com/Exerosis/ETCD.git",
                "git clone https://github.com/Exerosis/RabiaGo.git",
                "git clone https://github.com/Bompedy/RS-Paxos.git"
        ).forEach {
            ProcessBuilder().apply {
                directory(file)
                command(listOf("/bin/bash", "-c", it))
                redirectErrorStream(true)
                val process = start()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    println(line)
                }
                process.waitFor()
            }
        }

        val setup = if (algorithm == "raft") addresses.joinToString(prefix = "--initial-cluster ", separator = ",") {
            "node-${addresses.indexOf(it) + 1}=http://$it:12380"
        } else ""

        ProcessBuilder().apply {
            environment()["NODES"] = nodes.toString()
            environment()["RS_RABIA"] = (algorithm == "rabia").toString()
            environment()["RS_PAXOS"] = (algorithm == "paxos").toString()
            environment()["PINEAPPLE"] = (algorithm == "pineapple").toString()
            environment()["PINEAPPLE_MEMORY"] = (algorithm == "pineapple-memory").toString()
            environment()["FAILURES"] = failures?.toString() ?: "0"
            directory(file)

            command(listOf("/bin/bash", "-c", """
                git config --global --add safe.directory $directory/Raft &&
                git config --global --add safe.directory $directory/RabiaGo &&
                git config --global --add safe.directory $directory/PineappleGo &&
                git config --global --add safe.directory $directory/ETCD &&
                git config --global --add safe.directory $directory/RS-Paxos &&
                cd PineappleGo && git pull && cd .. &&
                cd RabiaGo && git pull && cd .. &&
                cd Raft && git pull && cd .. &&
                cd RS-Paxos && git pull && cd .. &&
                cd ETCD && git pull && sudo rm -rf $host.etcd && make build &&
                sudo ./bin/etcd \
                --log-level panic \
                --name "$host" \
                --initial-cluster-token etcd-cluster-1 \
                --listen-client-urls http://$ip:2379,http://127.0.0.1:2379 \
                --advertise-client-urls http://$ip:2379 \
                --initial-advertise-peer-urls http://$ip:12380 \
                --listen-peer-urls http://$ip:12380 \
                --quota-backend-bytes 10000000000 \
                --snapshot-count 0 \
                --max-request-bytes 104857600 \
                $setup \
                --initial-cluster-state new
            """.trimIndent()))

            redirectErrorStream(true)
            val process = start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                println(line)
            }
            process.waitFor()
        }
    } catch (exception: Exception) {
        exception.printStackTrace()
    }
}
package me.lucas.consensus

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.NetworkInterface
import kotlin.system.exitProcess

fun String.process(directory: File) {
    ProcessBuilder().apply {
        directory(directory)
        command(listOf("/bin/bash", "-c", this@process))
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

fun main(args: Array<String>) {
    val directory = args.find { it.startsWith("--directory=") }?.substringAfter("=")
    val algorithm = args.find { it.startsWith("--algorithm=") }?.substringAfter("=")
    val ips = args.find { it.startsWith("--ips=") }?.substringAfter("=")?.split(",")?.map { it.replace("\\s".toRegex(), "") }
    println("IPS: $ips")
    val failures = args.find { it.startsWith("--failures=") }?.substringAfter("=")?.toInt()
    val segments = args.find { it.startsWith("--segments=") }?.substringAfter("=")?.toInt()
    val transactionRead = args.find { it.startsWith("--trans_read") }?.substringAfter("=")?.toBoolean()
    val rabiaBranch = args.find { it.startsWith("--rabia-branch") }?.substringAfter("=")
    val etcdBranch = args.find { it.startsWith("--etcd-branch") }?.substringAfter("=")
    val paxosBranch = args.find { it.startsWith("--paxos-branch") }?.substringAfter("=")
    val guard: (Boolean, String) -> (Unit) = { invalid, message ->
        if (invalid) {
            println(message)
            exitProcess(1)
        }
    }
    val algorithms = arrayOf("bench", "raft", "rabia", "racos", "paxos", "pineapple", "pineapple-memory")
    guard(directory == null, "You must specify a directory with --directory=")
    guard(algorithm == null, "You must specify an algorithm with --algorithm= \nOptions - ${algorithms.joinToString(separator = ", ") { it }}")
    guard(algorithms.find { it.equals(algorithm, ignoreCase = false) } == null, "That is not an accepted algorithm \nOptions - ${algorithms.joinToString(separator = ", ") { it }}")
    guard(ips == null, "You must specify node ips seperated by commas with --ips=")
    ips!!; directory!!

    if (algorithm == "racos" || algorithm == "paxos") {
        guard(failures == null, "You must specify an amount of failures with --failures=")
        guard(failures!! > ips.size, "Failures must be less than ${ips.size} nodes")
        guard(segments == null, "You must specify an amount of segments with --segments=")
        if (algorithm == "racos") guard(transactionRead == null, "Must specify if transaction read is enabled with --trans_read=")
//        guard(segments!! > ips.size, "Segments must be less than ${ips.size} nodes")
        guard(ips.size < (2 * failures) + segments!!, "You must have >= nodes to segments and failures!")
    }

    val file = File(directory)
    if (!file.exists()) file.mkdirs()

    try {
        arrayOf(
            "git clone https://github.com/Exerosis/Raft.git",
            "git clone https://github.com/Exerosis/PineappleGo.git",
            "git clone https://github.com/Exerosis/ETCD.git",
            "git clone https://github.com/Exerosis/RabiaGo.git",
            "git clone https://github.com/Bompedy/RS-Paxos.git",
            "git clone https://github.com/Exerosis/go-ycsb.git"
        ).forEach { it.process(file) }


        if (algorithm.equals("bench")) {
            "cd go-ycsb && sudo make".trimIndent().process(file)
            return
        }

        val interfaces = NetworkInterface.getNetworkInterfaces()
        var ip: String? = null
        println("\nChecking interfaces: ${ips}")
        while (interfaces.hasMoreElements()) {
            val next = interfaces.nextElement()
            println("\nInterface: $next")
            val addresses = next.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                println("Address: ${address.hostAddress}")
                if (ips.contains(address.hostAddress)) {
                    println("Found IP!")
                    ip = address.hostAddress
                }
            }
        }

        println("Complete!\n")

        println("ip: $ip")
        guard(ip == null, "Can't find host!")
        val hostName = "node-${ips.indexOf(ip) + 1}"
//        guard(host == -1, "Can't find host address.")
        println("Host: $hostName")
        println("IP: $ip")
        println("Algorithim: $algorithm")


        val setup = if (algorithm == "raft") ips.joinToString(prefix = "--initial-cluster ", separator = ",") {
            "node-${ips.indexOf(it) + 1}=http://$it:12380"
        } else ""

        ProcessBuilder().apply {
            environment()["NODES"] = ips.joinToString(separator = ",") { it }
            environment()["RABIA"] = (algorithm == "rabia").toString()
            environment()["RACOS"] = (algorithm == "racos").toString()
            environment()["RS_PAXOS"] = (algorithm == "paxos").toString()
            environment()["PINEAPPLE"] = (algorithm == "pineapple").toString()
            environment()["PINEAPPLE_MEMORY"] = (algorithm == "pineapple-memory").toString()
            environment()["FAILURES"] = failures?.toString() ?: "0"
            environment()["TRANSACTION_READ"] = (transactionRead ?: false).toString()
            environment()["SEGMENTS"] = segments?.toString() ?: "0"
            directory(file)

            command(listOf("/bin/bash", "-c", """
                git config --global --add safe.directory $directory/Raft &&
                git config --global --add safe.directory $directory/RabiaGo &&
                git config --global --add safe.directory $directory/PineappleGo &&
                git config --global --add safe.directory $directory/ETCD &&
                git config --global --add safe.directory $directory/RS-Paxos &&
                cd PineappleGo && git fetch --all && git pull && cd .. &&
                cd RabiaGo && git fetch --all  ${if (rabiaBranch != null) "&& git checkout $rabiaBranch" else ""} && git pull && cd .. &&
                cd Raft && git fetch --all && git pull && cd .. &&
                cd RS-Paxos && git fetch --all ${if (paxosBranch != null) "&& git checkout $paxosBranch" else ""} && git pull && cd .. &&
                cd ETCD && git fetch --all ${if (etcdBranch != null) "&& git checkout $etcdBranch" else ""} && git pull && rm -rf $hostName.etcd && make build &&
                ./bin/etcd \
                --log-level panic \
                --name "$hostName" \
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
package com.kairos.tv

/**
 * Pacchetti del DNS cifrato (vedi [DnsShield]): legge la domanda DNS che il sistema manda all'interfaccia locale e costruisce
 * il pacchetto di risposta. Solo conti sui byte, niente Android: si prova sulla JVM (DnsWireTest).
 */
object DnsWire {
    class Query(val src: ByteArray, val dst: ByteArray, val srcPort: Int, val dstPort: Int, val payload: ByteArray)

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)

    /** pacchetto letto dall'interfaccia: vale solo IPv4 + UDP verso la porta 53, intero e non frammentato. Altrimenti null */
    fun parse(p: ByteArray, len: Int): Query? {
        if (len < 28 || len > p.size) return null
        if (u8(p, 0) shr 4 != 4) return null                    // solo IPv4
        val ihl = (u8(p, 0) and 0x0F) * 4
        if (ihl < 20 || len < ihl + 8) return null
        if (u8(p, 9) != 17) return null                         // solo UDP
        if (u16(p, 6) and 0x3FFF != 0) return null              // niente frammenti
        val total = u16(p, 2)
        if (total > len || total < ihl + 8) return null
        val dport = u16(p, ihl + 2)
        if (dport != 53) return null
        val ulen = u16(p, ihl + 4)
        if (ulen < 8 + 12 || ihl + ulen > total) return null    // almeno l'intestazione DNS (12 byte)
        return Query(p.copyOfRange(12, 16), p.copyOfRange(16, 20), u16(p, ihl), dport, p.copyOfRange(ihl + 8, ihl + ulen))
    }

    /** risposta da scrivere sull'interfaccia: IPv4 + UDP con indirizzi e porte scambiati rispetto alla domanda */
    fun reply(q: Query, dns: ByteArray): ByteArray {
        val total = 20 + 8 + dns.size
        val o = ByteArray(total)
        o[0] = 0x45
        o[2] = (total shr 8).toByte(); o[3] = total.toByte()
        o[6] = 0x40                                             // non frammentare
        o[8] = 64; o[9] = 17
        System.arraycopy(q.dst, 0, o, 12, 4); System.arraycopy(q.src, 0, o, 16, 4)
        val c = checksum(o, 0, 20); o[10] = (c shr 8).toByte(); o[11] = c.toByte()
        o[20] = (q.dstPort shr 8).toByte(); o[21] = q.dstPort.toByte()
        o[22] = (q.srcPort shr 8).toByte(); o[23] = q.srcPort.toByte()
        val ulen = 8 + dns.size
        o[24] = (ulen shr 8).toByte(); o[25] = ulen.toByte()   // somma di controllo UDP a zero: su IPv4 è facoltativa
        System.arraycopy(dns, 0, o, 28, dns.size)
        return o
    }

    /** somma di controllo di Internet (complemento a uno su parole di 16 bit) */
    fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i + 1 < off + len) { sum += u16(b, i); i += 2 }
        if (i < off + len) sum += (u8(b, i) shl 8)
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}

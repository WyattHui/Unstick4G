package com.wyatthui.unstick4g.logger

data class LteCell(val ci: Int?, val pci: Int?, val earfcn: Int?, val bands: List<Int>, val rsrp: Int?)

fun formatLteCells(cells: List<LteCell>) = cells.joinToString(";") { cell ->
    listOf(
        cell.ci?.toString(),
        cell.pci?.toString(),
        cell.earfcn?.toString(),
        cell.bands.joinToString("+"),
        cell.rsrp?.toString(),
    ).joinToString(":") { it.orEmpty() }
}

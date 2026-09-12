package com.nte.auction.scanner

import com.nte.auction.domain.ScanState
import com.nte.auction.domain.WarehouseScanSession

class ScanCoverageTracker {
    fun observeRows(session: WarehouseScanSession, firstRow: Int, lastRowInclusive: Int) {
        require(firstRow >= 0)
        require(lastRowInclusive >= firstRow)
        for (row in firstRow..lastRowInclusive) session.coveredRows += row
        session.maxObservedRow = maxOf(session.maxObservedRow, lastRowInclusive)
        if (session.totalRows != null && session.coverage >= 0.999) {
            session.state = ScanState.COMPLETE
        } else if (session.state == ScanState.IDLE || session.state == ScanState.WAITING_FOR_TOP) {
            session.state = ScanState.SCANNING
        }
    }

    fun markNeedOverlap(session: WarehouseScanSession) {
        session.state = ScanState.NEED_OVERLAP
    }

    fun setTotalRows(session: WarehouseScanSession, totalRows: Int) {
        require(totalRows > 0)
        session.totalRows = totalRows
        if (session.coverage >= 0.999) session.state = ScanState.COMPLETE
    }
}

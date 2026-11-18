package com.example.voucherflow.inventory.model;

/** 定义预留、确认、释放对三类库存的增减量，每次变更的总量保持不变。 */
public enum LedgerType {
    RESERVE(-1, 1, 0),
    CONFIRM(0, -1, 1),
    RELEASE(1, -1, 0);
    private final int deltaAvailable;
    private final int deltaReserved;
    private final int deltaIssued;

    LedgerType(int available, int reserved, int issued) {
        this.deltaAvailable = available;
        this.deltaReserved = reserved;
        this.deltaIssued = issued;
    }

    public int getDeltaAvailable() {
        return deltaAvailable;
    }

    public int getDeltaReserved() {
        return deltaReserved;
    }

    public int getDeltaIssued() {
        return deltaIssued;
    }
}

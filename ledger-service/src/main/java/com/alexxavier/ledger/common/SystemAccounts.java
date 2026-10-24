package com.alexxavier.ledger.common;

import java.util.UUID;

/** Well-known accounts created by Flyway migrations. */
public final class SystemAccounts {

    /** Source of deposits / sink of withdrawals. The only account allowed to go negative. */
    public static final UUID EXTERNAL = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private SystemAccounts() {
    }

    public static boolean isSystem(UUID id) {
        return EXTERNAL.equals(id);
    }
}

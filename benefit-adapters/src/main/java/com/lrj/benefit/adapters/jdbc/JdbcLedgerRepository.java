package com.lrj.benefit.adapters.jdbc;

import com.lrj.benefit.application.port.out.LedgerRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;

public final class JdbcLedgerRepository implements LedgerRepository {
    private final JdbcTemplate jdbc;
    public JdbcLedgerRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public boolean appendIfAbsent(LedgerEntry entry) {
        try {
            jdbc.update("""
                    INSERT INTO bc_award_ledger_entry
                    (tenant_id,ledger_no,order_no,item_no,operation_no,entry_type,amount_minor,quantity_signed,
                     currency,owner_type,channel_code,provider_reference,biz_time)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, entry.tenantId(), entry.ledgerNo(), entry.orderNo(), entry.itemNo(), entry.operationNo(),
                    entry.entryType(), entry.amountMinor(), entry.quantitySigned(), entry.currency(), entry.ownerType(),
                    entry.channelCode(), entry.providerReference(), Timestamp.from(entry.bizTime()));
            return true;
        } catch (DuplicateKeyException replay) {
            return false;
        }
    }
}

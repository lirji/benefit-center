package com.lrj.benefit.adapters.jdbc;

import com.lrj.benefit.application.port.out.CellRouter;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcCellRouter implements CellRouter {
    private final JdbcTemplate jdbc;
    private final String localCell;
    public JdbcCellRouter(JdbcTemplate jdbc, String localCell) { this.jdbc = jdbc; this.localCell = localCell; }

    @Override public String homeCell(String tenantId) {
        var cells = jdbc.query("""
                SELECT home_cell FROM bc_tenant_config WHERE tenant_id=? AND status='ENABLED'
                """, (rs, row) -> rs.getString(1), tenantId);
        if (cells.isEmpty()) throw new IllegalStateException("tenant is not enabled in benefit-center");
        return cells.getFirst();
    }

    @Override public boolean isLocal(String cellId) { return localCell.equals(cellId); }
}

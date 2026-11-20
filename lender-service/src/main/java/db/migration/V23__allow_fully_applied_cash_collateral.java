package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V23__allow_fully_applied_cash_collateral extends BaseJavaMigration {
  @Override
  public void migrate(Context context) throws SQLException {
    Connection connection = context.getConnection();
    String originalConstraint = findAssetConstraint(connection);
    try (var statement = connection.createStatement()) {
      statement.execute("""
          ALTER TABLE collateral_position ADD CONSTRAINT collateral_position_asset_shape
          CHECK (
            (asset_type = 'CASH' AND security_id IS NULL AND quantity = 0
              AND (cash_amount > 0 OR (cash_amount = 0 AND status = 'APPLIED')))
            OR (asset_type = 'SECURITY' AND security_id IS NOT NULL
              AND quantity > 0 AND cash_amount = 0)
          )
          """);
      statement.execute("ALTER TABLE collateral_position DROP CONSTRAINT \""
          + originalConstraint.replace("\"", "\"\"") + "\"");
    }
  }

  private String findAssetConstraint(Connection connection) throws SQLException {
    // V1 left the constraint unnamed, so PostgreSQL and H2 generate different names.
    try (var query = connection.prepareStatement("""
        SELECT DISTINCT tc.constraint_name
          FROM information_schema.table_constraints tc
          JOIN information_schema.check_constraints cc
            ON cc.constraint_catalog = tc.constraint_catalog
           AND cc.constraint_schema = tc.constraint_schema
           AND cc.constraint_name = tc.constraint_name
         WHERE tc.table_schema = ?
           AND tc.table_name = 'collateral_position'
           AND tc.constraint_type = 'CHECK'
           AND POSITION('ASSET_TYPE' IN UPPER(cc.check_clause)) > 0
           AND POSITION('CASH_AMOUNT' IN UPPER(cc.check_clause)) > 0
           AND POSITION('SECURITY_ID' IN UPPER(cc.check_clause)) > 0
        """)) {
      query.setString(1, connection.getSchema());
      try (var rows = query.executeQuery()) {
        if (!rows.next()) throw new SQLException("Missing collateral position asset constraint");
        String name = rows.getString(1);
        if (rows.next()) throw new SQLException("Multiple collateral position asset constraints");
        return name;
      }
    }
  }
}

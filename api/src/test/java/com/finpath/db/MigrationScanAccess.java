package com.finpath.db;

import java.io.IOException;
import java.util.List;

/** Public window onto the package-private MigrationScan for tests in other packages. */
public final class MigrationScanAccess {

    private MigrationScanAccess() {
    }

    public static String allCode() throws IOException {
        return MigrationScan.allCode();
    }

    public static List<String> tableNames(String code) {
        return MigrationScan.tables(code).stream().map(MigrationScan.Table::name).toList();
    }
}

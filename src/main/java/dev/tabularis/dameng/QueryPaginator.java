package dev.tabularis.dameng;

final class QueryPaginator {
    private QueryPaginator() {
    }

    static String paginated(String query, int limit, int page) {
        int safePage = Math.max(1, page);
        int safeLimit = Math.max(1, limit);
        int end = safePage * safeLimit + 1;
        return "SELECT * FROM (" + query + ") WHERE ROWNUM <= " + end;
    }
}

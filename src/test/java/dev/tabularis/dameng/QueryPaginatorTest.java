package dev.tabularis.dameng;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class QueryPaginatorTest {
    @Test
    void wrapsQueryWithRowNumLimitPlusOne() {
        assertEquals(
                "SELECT * FROM (SELECT * FROM T) WHERE ROWNUM <= 21",
                QueryPaginator.paginated("SELECT * FROM T", 10, 2)
        );
    }
}

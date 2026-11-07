package eu.neverblink.ohi.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PagesTest {
    @Test
    void linksAReleasePageOnOhiDev() {
        assertEquals("https://ohi.dev/o/w3c/sosa@2017", Pages.ohiReleasePage("w3c", "sosa", "2017"));
        SearchHit hit = new SearchHit();
        hit.setNamespace("w3c");
        hit.setName("sosa");
        hit.setVersion("2017");
        assertEquals("https://ohi.dev/o/w3c/sosa@2017", Pages.ohiReleasePage(hit));
    }

    @Test
    void encodesEachPartOfThePath() {
        assertEquals("https://ohi.dev/o/a%20b/x%2Fy%40z@1.0%2Bamendment.1%20%C3%A9",
                Pages.ohiReleasePage("a b", "x/y@z", "1.0+amendment.1 é"));
    }

    @Test
    void leavesTheSameCharactersAsJavaScript() {
        assertEquals("https://ohi.dev/o/-_.!~*'()/n@v", Pages.ohiReleasePage("-_.!~*'()", "n", "v"));
    }
}

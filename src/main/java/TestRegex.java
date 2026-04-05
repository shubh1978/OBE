import java.util.regex.*;

public class TestRegex {
    public static void main(String[] args) {
        String input = "CO1 3 - 2 2 - - - - - - 2 -\nCO2 3 3 2 2 - - - - - - 2 -";
        Pattern coRowPat = Pattern.compile("(CO\\d+)\\s+([0-3\\-\\s]{4,})");
        Matcher cm = coRowPat.matcher(input);
        while(cm.find()) {
            System.out.println("Match: " + cm.group(1) + " -> [" + cm.group(2) + "]");
        }
    }
}

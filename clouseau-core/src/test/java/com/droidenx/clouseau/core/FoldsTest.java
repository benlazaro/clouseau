package com.droidenx.clouseau.core;

import com.droidenx.clouseau.core.Folds.Fold;
import com.droidenx.clouseau.core.Folds.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FoldsTest {

    private static final String JSON = """
            Sent: {
              "user": {
                "id": 1,
                "name": "a{b]c"
              },
              "tags": [
                "x",
                "y"
              ],
              "empty": []
            }""";

    @Test
    void jsonFoldsEveryObjectAndArrayWithItsCount() {
        List<Fold> folds = Folds.json(JSON, JSON.indexOf('{'));
        assertThat(folds).extracting(Fold::open, Fold::count, Fold::kind).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(JSON.indexOf('{'), 3, Kind.KEYS),
                org.assertj.core.groups.Tuple.tuple(JSON.indexOf("{\n    \"id"), 2, Kind.KEYS),
                org.assertj.core.groups.Tuple.tuple(JSON.indexOf("[\n"), 2, Kind.ITEMS),
                org.assertj.core.groups.Tuple.tuple(JSON.indexOf("[]"), 0, Kind.ITEMS));
        Fold outer = folds.stream().filter(f -> f.open() == JSON.indexOf('{')).findFirst().orElseThrow();
        assertThat(outer.close()).isEqualTo(JSON.length() - 1);
    }

    @Test
    void mergeKeepsMultiLineFoldsInOrderAndDropsClashes() {
        List<Fold> merged = Folds.merge(JSON, List.of(Folds.json(JSON, JSON.indexOf('{'))));
        // The empty array is on one line: nothing to hide.
        assertThat(merged).extracting(Fold::open).containsExactly(
                JSON.indexOf('{'), JSON.indexOf("{\n    \"id"), JSON.indexOf("[\n"));

        String text = "(\n[\n)\n]";
        Fold a = new Fold(0, 4, 1, Kind.ITEMS), crossing = new Fold(2, 6, 1, Kind.ITEMS), same = new Fold(0, 4, 2, Kind.KEYS);
        assertThat(Folds.merge(text, List.of(List.of(a), List.of(crossing, same)))).containsExactly(a);
    }

    @Test
    void hiddenIsTheOutermostCollapsedFolds() {
        List<Fold> merged = Folds.merge(JSON, List.of(Folds.json(JSON, JSON.indexOf('{'))));
        int outer = JSON.indexOf('{'), user = JSON.indexOf("{\n    \"id"), tags = JSON.indexOf("[\n");
        assertThat(Folds.hidden(merged, Set.of(user, tags))).extracting(Fold::open).containsExactly(user, tags);
        assertThat(Folds.hidden(merged, Set.of(outer, user))).extracting(Fold::open).containsExactly(outer);
        assertThat(Folds.hidden(merged, Set.of())).isEmpty();
    }

    @Test
    void toStringFoldsNestedStructuresByKind() {
        String text = ToStringFormat.format("Saved Order(id=12, customer=Customer(name=Bob Smith, email=bob@example.com, "
                + "phone=555-0100), items=[Item(sku=A-1, qty=2, price=9.99), Item(sku=B-2, qty=1, price=19.99), Item(sku=C-3, qty=5, price=1.25)], "
                + "meta={source=web, region=us-east-1, trace=abcdef0123456789, session=0123456789abcdef}) in 5ms");
        List<Fold> folds = Folds.merge(text, List.of(Folds.toStrings(text)));
        Fold order = folds.get(0);
        assertThat(order.open()).isEqualTo(text.indexOf("Order(") + 5);
        assertThat(order.kind()).isEqualTo(Kind.FIELDS);
        assertThat(order.count()).isEqualTo(4);
        assertThat(folds).extracting(Fold::kind).contains(Kind.ITEMS, Kind.KEYS);
        assertThat(text.charAt(order.close())).isEqualTo(')');
    }
}

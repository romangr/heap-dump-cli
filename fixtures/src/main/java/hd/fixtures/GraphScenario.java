package hd.fixtures;

import hd.fixtures.model.ChainNode;
import hd.fixtures.model.DiamondNode;
import hd.fixtures.model.FixtureRoot;
import hd.fixtures.model.Payload;

/**
 * Structural fixture with known shapes (all reachable from the static field {@link FixtureRoot#INSTANCE}):
 *
 * <pre>
 * FixtureRoot.INSTANCE
 *   ├── chain:   ChainNode#0 → ChainNode#1 → … → ChainNode#9 (each holds a Payload of 1000 bytes)
 *   ├── diamond: top → left, right; left → bottom; right → bottom  (bottom dominated by top, not left/right)
 *   ├── cycle:   a → b → c → a  (a is referenced from root, so a dominates b and c)
 *   └── big:     Payload with 1 MiB byte[]
 * </pre>
 */
final class GraphScenario implements FixtureMain.Scenario {

    static final int CHAIN_LENGTH = 10;

    @Override
    public void build() {
        FixtureRoot root = new FixtureRoot();

        ChainNode head = null;
        for (int i = CHAIN_LENGTH - 1; i >= 0; i--) {
            head = new ChainNode(i, new Payload(1000), head);
        }
        root.chain = head;

        DiamondNode top = new DiamondNode("top");
        DiamondNode left = new DiamondNode("left");
        DiamondNode right = new DiamondNode("right");
        DiamondNode bottom = new DiamondNode("bottom");
        bottom.payload = new Payload(4096);
        top.a = left;
        top.b = right;
        left.a = bottom;
        right.a = bottom;
        root.diamond = top;

        DiamondNode a = new DiamondNode("cycle-a");
        DiamondNode b = new DiamondNode("cycle-b");
        DiamondNode c = new DiamondNode("cycle-c");
        a.a = b;
        b.a = c;
        c.a = a;
        root.cycle = a;

        root.big = new Payload(1 << 20);

        FixtureRoot.INSTANCE = root;
    }
}

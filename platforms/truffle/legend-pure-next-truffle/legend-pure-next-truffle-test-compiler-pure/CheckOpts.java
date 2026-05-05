import org.graalvm.polyglot.Engine;
public class CheckOpts {
    public static void main(String[] args) {
        try (Engine engine = Engine.create()) {
            int count = 0;
            for (var d : engine.getOptions()) {
                String n = d.getName();
                if (n.equals("engine.TraceCompilation")) System.out.println("FOUND " + n);
                count++;
            }
            System.out.println("Total options: " + count);
            System.out.println("Truffle runtime: " + com.oracle.truffle.api.Truffle.getRuntime().getName());
        }
    }
}

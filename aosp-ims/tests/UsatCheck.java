// Check the USAT decisions ImsStack patches 0005 and 0006 make, on the
// compiled classes: UsatAgent's own decode functions fed the envelope
// responses a RIL can return. joan's RIL completes an ENVELOPE (CALL
// CONTROL) with status words 00 00 and no data; before 0005 that failed
// every MO call on a SIM with call control by USIM.
//
// Run by tools/build-apk.sh:
//   javac -d <dir> UsatCheck.java
//   java -cp <dir>:<classes>:<module-lib android.jar> UsatCheck
// UsatAgent is allocated without its constructor (which needs a running
// app), so only its pure decode paths run.

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class UsatCheck {
    private static final String PKG = "com.android.imsstack.core.agents.";
    private static final int ALLOWED = 0;
    private static final int NOT_ALLOWED = 1;

    private static Class<?> sAgentClass;
    private static Object sAgent;
    private static int sFails;

    public static void main(String[] args) throws Exception {
        sAgentClass = Class.forName(PKG + "UsatAgent");
        Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Object unsafe = f.get(null);
        sAgent = unsafe.getClass().getMethod("allocateInstance", Class.class)
                .invoke(unsafe, sAgentClass);
        Field lock = sAgentClass.getDeclaredField("mLock");
        lock.setAccessible(true);
        lock.set(sAgent, new Object());

        // Call control: {response, expected result, what it is}
        String[][] callControl = {
            {"0000", "0", "no answer from the UICC: joan's RIL, SW 00 00"},
            {"", "0", "no answer from the UICC: the RIL failed the request"},
            {"9000", "0", "UICC: allowed, no modification"},
            {"9300", "1", "UICC: busy"},
            {"6F00", "1", "UICC: technical problem"},
            {"01009000", "1", "UICC: result 01, not allowed"},
            {"01000000", "1", "result 01 with the RIL's SW 00 00"},
            {"00009000", "0", "UICC: result 00, allowed"},
            {"00000000", "0", "result 00 with the RIL's SW 00 00"},
        };
        for (String[] c : callControl) {
            expect("call control " + c[2], Integer.parseInt(c[1]),
                    decode("decodeCommandResponseForCallControl", command(
                            "Usat$CallControlCommand", 1, null, 1, "+15550100", 13, 1), c[0]));
        }
        String[][] moSmsControl = {
            {"0000", "0"}, {"", "0"}, {"9000", "0"}, {"9300", "1"}, {"01000000", "1"},
        };
        for (String[] c : moSmsControl) {
            expect("MO SMS control \"" + c[0] + "\"", Integer.parseInt(c[1]),
                    decode("decodeCommandResponseForMoSmsControl", command(
                            "Usat$MoSmsControlCommand", 2, null, "+15550101", "+15550102", 13),
                            c[0]));
        }

        // Location information PLMN (3GPP TS 24.008 10.5.1.3)
        Method encode = sAgentClass.getDeclaredMethod("encodePlmn", String.class, String.class);
        encode.setAccessible(true);
        String[][] plmns = {
            {"310", "260", "130062"}, {"310", "26", "13F062"}, {"450", "05", "54F050"},
            {"405", "854", "044558"}, {"31", "260", null}, {"310", "2600", null},
        };
        for (String[] p : plmns) {
            Object got = encode.invoke(null, p[0], p[1]);
            boolean ok = p[2] == null ? got == null : p[2].equals(got);
            if (!ok) {
                sFails++;
                System.out.println("FAIL encodePlmn(" + p[0] + ", " + p[1] + ") = " + got
                        + ", want " + p[2]);
            }
        }

        System.out.println("USAT decisions: "
                + (callControl.length + moSmsControl.length + plmns.length) + " cases, "
                + (sFails == 0 ? "OK" : sFails + " FAILURES"));
        System.exit(sFails == 0 ? 0 : 1);
    }

    private static void expect(String what, int want, int got) {
        if (want != got) {
            sFails++;
            System.out.println("FAIL " + what + ": result " + got + ", want " + want);
        }
    }

    private static int decode(String method, Object cmd, String response) throws Exception {
        Method create = sAgentClass.getDeclaredMethod("createUsatResult", String.class);
        create.setAccessible(true);
        Object result = create.invoke(null, response);
        for (Method m : sAgentClass.getDeclaredMethods()) {
            if (m.getName().equals(method)) {
                m.setAccessible(true);
                Object res = m.invoke(sAgent, cmd, result);
                Method getResult = res.getClass().getMethod("getResult");
                getResult.setAccessible(true);
                return (Integer) getResult.invoke(res);
            }
        }
        throw new NoSuchMethodException(method);
    }

    private static Object command(String name, Object... args) throws Exception {
        Constructor<?> k = Class.forName(PKG + name).getDeclaredConstructors()[0];
        k.setAccessible(true);
        return k.newInstance(args);
    }
}

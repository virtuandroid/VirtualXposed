package mirror.android.content;

import android.util.ArraySet;

import mirror.RefClass;
import mirror.RefObject;

/**
 * @author Alberto Lazari
 * @date 2024/5/13
 */

public class IntentFilterUpsideDownCake {
    public static Class Class = RefClass.load(IntentFilterUpsideDownCake.class, android.content.IntentFilter.class);
    // Android 14 changed the type `java.lang.ArrayList` -> `android.util.ArraySet`
    public static RefObject<ArraySet<String>> mActions;
}

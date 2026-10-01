package mirror.android.content.pm;

import android.content.pm.Signature;

import mirror.MethodReflectParams;
import mirror.RefClass;
import mirror.RefConstructor;
import mirror.RefMethod;
import mirror.RefObject;

/**
 * @author Alberto Lazari
 * @date 2024/5/6.
 *
 * Android 14 implementation of the class, also found in android.content.pm.PackageParser$SigningDetails
 */

public class SigningDetails {
    public static Class<?> TYPE = RefClass.load(SigningDetails.class, "android.content.pm.SigningDetails");
    @MethodReflectParams({
        "[Landroid.content.pm.Signature;",
        "int",
        "[Landroid.content.pm.Signature;"
    })
    public static RefConstructor<SigningDetails> ctor;
}

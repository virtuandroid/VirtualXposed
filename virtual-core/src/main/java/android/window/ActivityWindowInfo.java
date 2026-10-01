package android.window;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

public final class ActivityWindowInfo implements Parcelable {
    private ActivityWindowInfo(Parcel in) {
    }

    public static final Creator<ActivityWindowInfo> CREATOR = new Creator<ActivityWindowInfo>() {
        @Override
        public ActivityWindowInfo createFromParcel(Parcel in) {
            return new ActivityWindowInfo(in);
        }

        @Override
        public ActivityWindowInfo[] newArray(int size) {
            return new ActivityWindowInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int i) {

    }
}

package net.bunny.bunnystreamplayer.model

import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.DrawableRes
import net.bunny.player.R

data class PlayerIconSet(
    @DrawableRes
    val playIcon: Int = R.drawable.ic_play_48dp,

    @DrawableRes
    val pauseIcon: Int = R.drawable.ic_pause_48dp,

    @DrawableRes
    val rewindIcon: Int = R.drawable.ic_replay_10s_48dp,

    @DrawableRes
    val forwardIcon: Int = R.drawable.ic_forward_10s_48dp,

    @DrawableRes
    val settingsIcon: Int = R.drawable.ic_settings_24dp,

    @DrawableRes
    val volumeOnIcon: Int = R.drawable.ic_volume_on_24dp,

    @DrawableRes
    val volumeOffIcon: Int = R.drawable.ic_volume_off_24dp,

    @DrawableRes
    val fullscreenOnIcon: Int = R.drawable.ic_fullscreen_24dp,

    @DrawableRes
    val fullscreenOffIcon: Int = R.drawable.ic_fullscreen_exit_24dp,
) : Parcelable {
    
    constructor(parcel: Parcel) : this(
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(playIcon)
        parcel.writeInt(pauseIcon)
        parcel.writeInt(rewindIcon)
        parcel.writeInt(forwardIcon)
        parcel.writeInt(settingsIcon)
        parcel.writeInt(volumeOnIcon)
        parcel.writeInt(volumeOffIcon)
        parcel.writeInt(fullscreenOnIcon)
        parcel.writeInt(fullscreenOffIcon)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<PlayerIconSet> {
        override fun createFromParcel(parcel: Parcel): PlayerIconSet {
            return PlayerIconSet(parcel)
        }

        override fun newArray(size: Int): Array<PlayerIconSet?> {
            return arrayOfNulls(size)
        }
    }
}

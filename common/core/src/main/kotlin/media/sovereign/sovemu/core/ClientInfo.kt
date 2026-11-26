package media.sovereign.sovemu.core

/**
 * Which app this is, as each app's own Hilt module says (`ClientInfoModule`), as
 * Sovereign.Android's: the only thing that knows which surface a build is. [tv] is the Android
 * TV and Fire TV apps (the D-pad, the TV keyboard, no touch controller); false, the phone and
 * tablet app.
 */
data class ClientInfo(val tv: Boolean)

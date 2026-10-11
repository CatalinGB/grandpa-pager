package com.grandpapager

/** Localised short label for a role, for user-facing status text. */
fun Role.labelRes(): Int = when (this) {
    Role.RECEIVER -> R.string.role_label_receiver
    Role.SENDER_ADMIN -> R.string.role_label_admin
    Role.SENDER_FAMILY -> R.string.role_label_family
}

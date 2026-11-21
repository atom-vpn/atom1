package com.atomvpn.mobile.engine.atom

/**
 * Second Psiphon instance for "two tunnels at once". psi.Start is a singleton per
 * process, so the second tunnel needs its own process (:Atom2, see the manifest).
 */
class AtomPsiphonSidecarService : AtomPsiphonService()

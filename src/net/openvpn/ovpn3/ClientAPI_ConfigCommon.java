/* ----------------------------------------------------------------------------
 * Hand-written (3.02), NOT SWIG-generated.
 *
 * A non-owning view of a ClientAPI_Config as its base class
 * openvpn::ClientAPI::ConfigCommon (ovpn3 3.12: Config IS-A
 * ConfigCommon, single inheritance, base subobject at offset 0,
 * so the very same native pointer is a valid ConfigCommon*).
 *
 * WHY THIS EXISTS: the shipped libovpn3.so has NO native
 * implementation for the ClientAPI_Config_* setters of
 * disableClientCert / connTimeout / tunPersist /
 * retryOnAuthFailed / clockTickMS / guiVersion (verified with
 * nm -D: only 17 ClientAPI_Config_* symbols exist, none of
 * those six) — calling the stale SWIG setters in
 * ClientAPI_Config.java throws UnsatisfiedLinkError on device.
 * The same fields DO exist under ConfigCommon_* (80 symbols).
 * The ConfigCommon setters below are the only way to set them.
 *
 * OWNERSHIP: this view NEVER owns the pointer. swigCMemOwn is
 * always false, delete() never calls any native delete, and
 * delete_ConfigCommon is deliberately NOT declared in
 * ovpncliJNI — the ClientAPI_Config that created the pointer
 * owns it and frees it. Construct only via
 * ClientAPI_Config.asConfigCommon().
 * ----------------------------------------------------------------------------- */

package net.openvpn.ovpn3;

public class ClientAPI_ConfigCommon {
  private transient long swigCPtr;
  protected transient boolean swigCMemOwn;

  protected ClientAPI_ConfigCommon(long cPtr, boolean cMemoryOwn) {
    swigCMemOwn = cMemoryOwn;
    swigCPtr = cPtr;
  }

  protected static long getCPtr(ClientAPI_ConfigCommon obj) {
    return (obj == null) ? 0 : obj.swigCPtr;
  }

  @SuppressWarnings("deprecation")
  protected void finalize() {
    delete();
  }

  public synchronized void delete() {
    // Non-owning view: never free the pointer. The
    // owning ClientAPI_Config frees it. Just drop
    // our reference so a second delete() is a no-op.
    swigCPtr = 0;
  }

  public void setDisableClientCert(boolean value) {
    ovpncliJNI.ConfigCommon_disableClientCert_set(swigCPtr, this, value);
  }

  public void setConnTimeout(int value) {
    ovpncliJNI.ConfigCommon_connTimeout_set(swigCPtr, this, value);
  }

  public void setTunPersist(boolean value) {
    ovpncliJNI.ConfigCommon_tunPersist_set(swigCPtr, this, value);
  }

  public void setRetryOnAuthFailed(boolean value) {
    ovpncliJNI.ConfigCommon_retryOnAuthFailed_set(swigCPtr, this, value);
  }

  public void setClockTickMS(long value) {
    ovpncliJNI.ConfigCommon_clockTickMS_set(swigCPtr, this, value);
  }

  public void setGuiVersion(String value) {
    ovpncliJNI.ConfigCommon_guiVersion_set(swigCPtr, this, value);
  }
}

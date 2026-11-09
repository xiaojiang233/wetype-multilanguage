// JNI wrapper over librime's C API (rime_api.h), backing top.xiaojiang233.wetypemulti.xposed.NativeRimeBridge.
#include <jni.h>
#include <rime_api.h>
#include <string>
#include <vector>

static RimeApi* api() { return rime_get_api(); }

static std::string str(JNIEnv* env, jstring s) {
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string r(c ? c : "");
  if (c) env->ReleaseStringUTFChars(s, c);
  return r;
}

extern "C" {

JNIEXPORT void JNICALL Java_top_xiaojiang233_wetypemulti_xposed_NativeRimeBridge_nInit(
    JNIEnv* env, jobject, jstring shared, jstring user, jstring app) {
  static std::string s_shared, s_user, s_app;  // RimeTraits keeps raw pointers
  s_shared = str(env, shared); s_user = str(env, user); s_app = str(env, app);
  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = s_shared.c_str();
  traits.user_data_dir = s_user.c_str();
  traits.app_name = s_app.c_str();
  traits.log_dir = s_user.c_str();  // glog defaults to /tmp, which does not exist on Android
  traits.min_log_level = 1;         // WARNING and up: INFO writes a line per deploy step, and each process start opens a new file
  traits.distribution_name = "WeTypeMulti";
  traits.distribution_code_name = "wetype-multi";
  traits.distribution_version = "0.1";
  api()->setup(&traits);
  api()->initialize(&traits);
}

JNIEXPORT jboolean JNICALL Java_top_xiaojiang233_wetypemulti_xposed_NativeRimeBridge_nDeploy(JNIEnv*, jobject) {
  bool ok = api()->start_maintenance(True);
  api()->join_maintenance_thread();
  return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_top_xiaojiang233_wetypemulti_xposed_NativeRimeBridge_nCreateSession(JNIEnv*, jobject) {
  return (jlong)api()->create_session();
}

JNIEXPORT void JNICALL Java_top_xiaojiang233_wetypemulti_xposed_NativeRimeBridge_nDestroySession(JNIEnv*, jobject, jlong s) {
  api()->destroy_session((RimeSessionId)s);
}

JNIEXPORT jboolean JNICALL Java_top_xiaojiang233_wetypemulti_xposed_NativeRimeBridge_nSelectSchema(
    JNIEnv* env, jobject, jlong s, jstring id) {
  return api()->select_schema((RimeSessionId)s, str(env, id).c_str()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_top_xiaojiang233_wetypemulti_xposed_NativeRimeBridge_nProcessKey(
    JNIEnv*, jobject, jlong s, jint code, jint mask) {
  return api()->process_key((RimeSessionId)s, code, mask) ? JNI_TRUE : JNI_FALSE;
}

// Returns [preedit, text0, comment0, text1, comment1, ...] for the current page.
JNIEXPORT jobjectArray JNICALL Java_top_xiaojiang233_wetypemulti_xposed_NativeRimeBridge_nContext(JNIEnv* env, jobject, jlong s) {
  RIME_STRUCT(RimeContext, ctx);
  std::vector<std::string> out;
  if (api()->get_context((RimeSessionId)s, &ctx)) {
    out.push_back(ctx.composition.preedit ? ctx.composition.preedit : "");
    for (int i = 0; i < ctx.menu.num_candidates; ++i) {
      out.push_back(ctx.menu.candidates[i].text ? ctx.menu.candidates[i].text : "");
      out.push_back(ctx.menu.candidates[i].comment ? ctx.menu.candidates[i].comment : "");
    }
    api()->free_context(&ctx);
  } else {
    out.push_back("");
  }
  jclass sc = env->FindClass("java/lang/String");
  jobjectArray arr = env->NewObjectArray((jsize)out.size(), sc, nullptr);
  for (size_t i = 0; i < out.size(); ++i) env->SetObjectArrayElement(arr, (jsize)i, env->NewStringUTF(out[i].c_str()));
  return arr;
}

JNIEXPORT jboolean JNICALL Java_top_xiaojiang233_wetypemulti_xposed_NativeRimeBridge_nSelectCandidate(
    JNIEnv*, jobject, jlong s, jint index) {
  return api()->select_candidate_on_current_page((RimeSessionId)s, (size_t)index) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_top_xiaojiang233_wetypemulti_xposed_NativeRimeBridge_nTakeCommit(JNIEnv* env, jobject, jlong s) {
  RIME_STRUCT(RimeCommit, commit);
  if (!api()->get_commit((RimeSessionId)s, &commit)) return nullptr;
  jstring r = env->NewStringUTF(commit.text ? commit.text : "");
  api()->free_commit(&commit);
  return r;
}

JNIEXPORT void JNICALL Java_top_xiaojiang233_wetypemulti_xposed_NativeRimeBridge_nClear(JNIEnv*, jobject, jlong s) {
  api()->clear_composition((RimeSessionId)s);
}

JNIEXPORT void JNICALL Java_top_xiaojiang233_wetypemulti_xposed_NativeRimeBridge_nFinalize(JNIEnv*, jobject) {
  api()->finalize();
}

}  // extern "C"

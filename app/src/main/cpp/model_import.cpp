#include <jni.h>
#include <assimp/Importer.hpp>
#include <assimp/Exporter.hpp>
#include <assimp/DefaultIOSystem.h>
#include <assimp/postprocess.h>
#include <assimp/config.h>
#include <assimp/scene.h>
#include <filesystem>
#include <string>
#include <stdexcept>
#include <algorithm>

class SandboxIO final : public Assimp::DefaultIOSystem {
    std::string root;
    std::string base;
    std::string resolve(const char* path) const {
        try {
            std::string name(path);std::replace(name.begin(),name.end(),'\\','/');
            std::filesystem::path candidate(name);
            if(name.find(':')!=std::string::npos)candidate=candidate.filename();
            if(candidate.is_relative())candidate=std::filesystem::path(base)/candidate;
            std::string p=std::filesystem::weakly_canonical(candidate).string();
            return p.compare(0,root.size(),root)==0 ? p : std::string();
        } catch (...) { return std::string(); }
    }
public:
    SandboxIO(const std::string& directory,const std::string& source): root(std::filesystem::canonical(directory).string()+"/"), base(std::filesystem::path(source).parent_path().string()) {}
    bool Exists(const char* path) const override {const auto p=resolve(path);return !p.empty() && DefaultIOSystem::Exists(p.c_str());}
    Assimp::IOStream* Open(const char* path, const char* mode="rb") override {
        const auto p=resolve(path);
        if (p.empty() || std::string(mode).find_first_of("wa+")!=std::string::npos) return nullptr;
        return DefaultIOSystem::Open(p.c_str(),mode);
    }
};
static std::string utf(JNIEnv* env,jstring s) {
    const char* p=env->GetStringUTFChars(s,nullptr);if(!p)throw std::runtime_error("Invalid path");
    std::string result(p);env->ReleaseStringUTFChars(s,p);return result;
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_huanban_ar_NativeModelCodec_convertNative(JNIEnv* env,jobject,jstring source,jstring target,jstring root) {
    try {
        const auto input=utf(env,source), output=utf(env,target), directory=utf(env,root);
        Assimp::Importer importer;
        importer.SetIOHandler(new SandboxIO(directory,input));
        importer.SetPropertyInteger(AI_CONFIG_PP_LBW_MAX_WEIGHTS,4);
        importer.SetPropertyInteger(AI_CONFIG_PP_SBBC_MAX_BONES,256);
        const aiScene* scene=importer.ReadFile(input,aiProcess_Triangulate|aiProcess_JoinIdenticalVertices|
            aiProcess_GenSmoothNormals|aiProcess_LimitBoneWeights|aiProcess_SplitByBoneCount|aiProcess_EmbedTextures);
        if(!scene || !scene->mRootNode || !scene->HasMeshes()) throw std::runtime_error(importer.GetErrorString());
        size_t vertices=0;
        for(unsigned i=0;i<scene->mNumMeshes;i++) vertices+=scene->mMeshes[i]->mNumVertices;
        if(vertices>750000 || scene->mNumMeshes>2048) throw std::runtime_error("Model exceeds geometry budget");
        Assimp::Exporter exporter;
        if(exporter.Export(scene,"glb2",output)!=AI_SUCCESS) throw std::runtime_error(exporter.GetErrorString());
        return nullptr;
    } catch(const std::exception& e) { return env->NewStringUTF(e.what()); }
    catch(...) { return env->NewStringUTF("Native model conversion failed"); }
}

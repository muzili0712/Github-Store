package zed.rainxch.core.data.services.dhizuku;

interface IDhizukuInstallerService {
    int installPackage(in ParcelFileDescriptor pfd, long fileSize, String expectedPackageName, long expectedVersionCode);
    int uninstallPackage(String packageName);
    void destroy();
}

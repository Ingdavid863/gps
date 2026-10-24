"""Collect native screenshots even on test failure, preserving Gradle's exit status."""
import pathlib
import subprocess
import sys

classes = ['com.david.gps3dar.WalkingHandoffTest', 'com.david.gps3dar.ArHudTest', 'com.david.gps3dar.SharedDestinationIntentTest', 'com.david.gps3dar.NavigationStateTest', 'com.david.gps3dar.MapControlsTest', 'com.david.gps3dar.NavigationRenderTest', 'com.david.gps3dar.LocationPrecisionTest', 'com.david.gps3dar.RouteTrafficVisibilityTest', 'com.david.gps3dar.MapTrafficTest', 'com.david.gps3dar.TollRoutingTest', 'com.david.gps3dar.SearchClearTest', 'com.david.gps3dar.CarNavigationTest', 'com.david.gps3dar.OfflineRouteRecoveryTest', 'com.david.gps3dar.WholeRouteOfflineMapTest', 'com.david.gps3dar.MusicPlaybackTest', 'com.david.gps3dar.ExternalMusicControlTest', 'com.david.gps3dar.InAppAudioTest', 'com.david.gps3dar.MusicPlayerScreenTest']
result = subprocess.run(['gradle', 'connectedDebugAndroidTest',
    '-Pandroid.testInstrumentationRunnerArguments.class=' + ','.join(classes), '--stacktrace'])
output = pathlib.Path('build/ar-native-qa')
output.mkdir(parents=True, exist_ok=True)
for source in ('/sdcard/Download/GPS3DARQA', '/sdcard/Download/GPS3DQA'):
    subprocess.run(['adb', 'pull', source, str(output)])
sys.exit(result.returncode)

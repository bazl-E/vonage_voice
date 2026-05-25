#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint vonage_voice.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'vonage_voice'
  s.version          = '0.0.1'
  s.summary          = 'Plugin for vonage voice'
  s.description      = <<-DESC
Plugin for vonage voice
                       DESC
  s.homepage         = 'http://example.com'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Your Company' => 'email@example.com' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*'
  s.static_framework = true
  s.dependency 'Flutter'
  s.dependency 'VonageClientSDKVoice', '~> 2.3'
  s.platform = :ios, '13.0'
  s.frameworks = 'CallKit', 'PushKit', 'AVFoundation', 'UIKit', 'UserNotifications'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.0'

  s.resource_bundles = {'vonage_voice_privacy' => ['Resources/PrivacyInfo.xcprivacy']}
end

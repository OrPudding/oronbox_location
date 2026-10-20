Pod::Spec.new do |s|
  s.name = 'oronbox_location'
  s.version = '0.1.0'
  s.summary = 'Native location support for OronBox'
  s.description = 'Single-fix and session-based location with explicit authorization'
  s.homepage = 'https://github.com/OrPudding/oronbox_location'
  s.license = { :file => '../LICENSE' }
  s.author = { 'OrPudding' => 'orpudding@users.noreply.github.com' }
  s.source = { :path => '.' }
  s.source_files = 'Classes/**/*'
  s.dependency 'Flutter'
  s.platform = :ios, '13.0'
  s.swift_version = '5.0'
  s.frameworks = 'CoreLocation'
end

#version 330

layout(std140) uniform LightmapInfo {
    float SkyFactor;
    float BlockFactor;
    float NightVisionFactor;
    float DarknessScale;
    float BossOverlayWorldDarkeningFactor;
    float BrightnessFactor;
    vec3 BlockLightTint;
    vec3 SkyLightColor;
    vec3 AmbientColor;
    vec3 NightVisionColor;
} lightmapInfo;

in vec2 texCoord;

out vec4 fragColor;

float get_brightness(float level) {
    return level / (4.0 - 3.0 * level);
}

vec3 notGamma(vec3 color) {
    float maxComponent = max(max(color.x, color.y), color.z);

    if (maxComponent <= 0.0001) {
        return color;
    }

    float maxInverted = 1.0 - maxComponent;
    float maxScaled = 1.0 - maxInverted * maxInverted * maxInverted * maxInverted;
    return color * (maxScaled / maxComponent);
}

void main() {
    float block_brightness = get_brightness(floor(texCoord.x * 16.0) / 15.0) * lightmapInfo.BlockFactor;
    float sky_brightness   = get_brightness(floor(texCoord.y * 16.0) / 15.0) * lightmapInfo.SkyFactor;

    block_brightness = smoothstep(0.0, 0.25, block_brightness) * block_brightness;

    vec3 color = vec3(
        block_brightness,
        block_brightness * ((block_brightness * 0.6 + 0.4) * 0.6 + 0.4),
        block_brightness * (block_brightness * block_brightness * 0.6 + 0.4)
    );

    // AmbientLightFactor no longer exists as a separate uniform (removed upstream) -- AmbientColor
    // itself is now the dimension's ambient-light baseline directly (black where there's none, e.g.
    // Overworld/End; a real color where there is, e.g. Nether), so folding it in via max() reproduces
    // the original "mix toward ambient" intent using the current mechanism.
    color = max(color, lightmapInfo.AmbientColor);

    float nightAttenuation = max(pow(lightmapInfo.SkyFactor, 2.0), 0.18);
    color += lightmapInfo.SkyLightColor * sky_brightness * nightAttenuation * 2.0;

    color = mix(color, vec3(0.75), 0.04);

    // Replaces the old "AmbientLightFactor == 0.0" gate (dimension has no inherent ambient light) --
    // same check, expressed against AmbientColor now that it's a color instead of a separate scalar.
    float ambientMax = max(max(lightmapInfo.AmbientColor.r, lightmapInfo.AmbientColor.g), lightmapInfo.AmbientColor.b);
    bool noAmbientLight = ambientMax <= 0.0001;

    if (noAmbientLight) {
        vec3 darkened_color = color * vec3(0.7, 0.6, 0.6);
        color = mix(color, darkened_color, lightmapInfo.BossOverlayWorldDarkeningFactor);
    }

    if (lightmapInfo.NightVisionFactor > 0.0) {
        float max_component = max(color.r, max(color.g, color.b));
        if (max_component < 1.0 && max_component > 0.0001) {
            vec3 bright_color = color / max_component;
            color = mix(color, bright_color, lightmapInfo.NightVisionFactor);
        }
    } else {
	    float light = block_brightness + sky_brightness;

	    // 1.0 = full cave / no skylight
	    float caveFactor = 1.0 - smoothstep(0.015, 0.08, sky_brightness);

	    // 1.0 = shaded under trees / partial sky blockage
	    // 0.0 = open sky
	    float shadeFactor = 1.0 - smoothstep(0.12, 0.45, sky_brightness);

	    // Remove actual caves from shadeFactor so caves use cave logic instead
	    shadeFactor *= (1.0 - caveFactor);

	    // Dark caves hard
	    float caveDarkness = smoothstep(0.0, 0.065, light);
	    caveDarkness *= mix(0.72, 1.0, smoothstep(0.02, 0.18, light));

	    // Open sky night: dark but playable
	    float outsideDarkness = smoothstep(0.0, 0.035, light);
	    outsideDarkness = max(outsideDarkness, 0.50);

	    // Under trees / canopy shade: slightly darker than open sky
	    float shadedDarkness = outsideDarkness * 0.20;

	    // Blend open sky -> shade -> cave
	    float darkness = outsideDarkness;
	    darkness = mix(darkness, shadedDarkness, shadeFactor);
	    darkness = mix(darkness, caveDarkness, caveFactor);

	    color *= darkness;

	    // Keep true caves near pitch black
	    if (caveFactor > 0.6 && light < 0.08) {
		    color *= 0.35;
	    }

	    if (noAmbientLight) {
		    color = color - vec3(lightmapInfo.DarknessScale * caveFactor);
	    }
    }

    color = clamp(color, 0.0, 1.0);

    vec3 notGammaColor = notGamma(color);

    // Let brightness setting help outdoors more than caves
    float caveFactor = 1.0 - smoothstep(0.015, 0.08, sky_brightness);
    float brightnessAllowed = mix(1.0, 0.12, caveFactor);

    color = mix(color, notGammaColor, lightmapInfo.BrightnessFactor * brightnessAllowed);

    fragColor = vec4(color, 1.0);
}

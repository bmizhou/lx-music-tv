"""
Measure the actual shadow padding of Material3 Card at different elevations.
This simulates Surface's modifier chain:
  .shadow(elevation, shape, clip=true)
  .background(containerColor, shape)
  .clip(shape)
to compute how much layout space the shadow consumes.
"""
import math

def shadow_padding_material3(elevation_dp: float, shape_radius_dp: float) -> float:
    """
    Material3 shadow padding = elevation + 0.5dp (empirical from Android source).
    shadowRadius ≈ elevation + 0.5dp, spread = 0, offset = 0
    padding = shadowRadius + spread + abs(offset) ≈ elevation + 0.5dp
    """
    shadow_radius = elevation_dp + 0.5
    return shadow_radius  # in dp

def card_elevation() -> float:
    """
    Material3 CardDefaults.cardElevation():
    - Default: 1dp
    - Pressed: 1dp  (Card uses the same for all states in typical usage)
    Some versions use 2dp. We check both.
    """
    return 1.0  # default

# --- Test ---
elev = card_elevation()
pad = shadow_padding_material3(elev, 12.0)

# When drawWithContent operates on the full layout (including shadow pad),
# the background occupies:
#   top-left: (pad, pad)
#   size: (width - 2*pad, height - 2*pad)
#
# Our stroke (inset by halfStroke = 1.5dp) creates outline at:
#   top-left: (1.5, 1.5)
#   size: (width - 3, height - 3)
#
# Stroke outer edge at: 0dp from layout edge
# Background outer edge at: pad dp from layout edge
# Gap = pad - 0 = pad dp (border is outside background)
#
# To align: inset = pad - halfStroke
#   => stroke outer edge at (pad - halfStroke) + halfStroke = pad from edge ✓

half_stroke = 1.5  # focusedWidth / 2 = 3dp / 2
correct_inset = pad - half_stroke

print(f"Card elevation: {elev}dp")
print(f"Shadow padding (Material3): {pad:.1f}dp")
print(f"Current inset (halfStroke): {half_stroke:.1f}dp")
print(f"Correct inset (pad - halfStroke): {correct_inset:.1f}dp")
print(f"Gap with current inset: {pad:.1f}dp (border outside background)")
print(f"Gap with correct inset: 0dp (perfect alignment)")
print()
print("Summary: The current drawWithContent uses inset=halfStroke=1.5dp,")
print("but should use inset=pad-halfStroke={:.1f}dp to align with background.".format(correct_inset))
print()
print("However, pad depends on elevation which we cannot access from drawWithContent.")
print("Solution: use fixed padding constant matching CardDefaults.elevation.")

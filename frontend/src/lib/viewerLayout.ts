/**
 * CSS width (px) at which a whole page fits the available area — the reader's "100%" zoom.
 * The page is limited by whichever side runs out first: width for a wide page on a tall
 * screen, height (× aspect ratio) for a portrait page on a wide screen.
 */
export function computeFitWidth(availableWidth: number, availableHeight: number, aspectRatio: number): number {
  if (availableWidth <= 0 || availableHeight <= 0 || !(aspectRatio > 0)) return 0;
  return Math.min(availableWidth, availableHeight * aspectRatio);
}

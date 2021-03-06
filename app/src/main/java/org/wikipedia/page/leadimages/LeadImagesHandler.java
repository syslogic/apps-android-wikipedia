package org.wikipedia.page.leadimages;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.location.Location;
import android.support.annotation.ColorInt;
import android.support.annotation.DimenRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentActivity;
import android.text.Html;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import org.json.JSONException;
import org.json.JSONObject;
import org.wikipedia.R;
import org.wikipedia.analytics.GalleryFunnel;
import org.wikipedia.page.Page;
import org.wikipedia.page.PageTitle;
import org.wikipedia.WikipediaApp;
import org.wikipedia.bridge.CommunicationBridge;
import org.wikipedia.page.PageFragment;
import org.wikipedia.page.gallery.GalleryActivity;
import org.wikipedia.savedpages.DeleteSavedPageTask;
import org.wikipedia.savedpages.SavePageTask;
import org.wikipedia.savedpages.SavedPage;
import org.wikipedia.util.DimenUtil;
import org.wikipedia.util.FeedbackUtil;
import org.wikipedia.util.ShareUtil;
import org.wikipedia.util.UriUtil;
import org.wikipedia.views.ObservableWebView;

import static org.wikipedia.util.DimenUtil.getContentTopOffsetPx;

public class LeadImagesHandler {
    /**
     * Minimum screen height for enabling lead images. If the screen is smaller than
     * this height, lead images will not be displayed, and will be substituted with just
     * the page title.
     */
    private static final int MIN_SCREEN_HEIGHT_DP = 480;

    /**
     * Maximum height of the page title text. If the text overflows this size, then the
     * font size of the title will be automatically reduced to fit.
     */
    private static final int TITLE_MAX_HEIGHT_DP = 256;

    /**
     * Minimum font size of the page title. Will not be reduced any further than this.
     */
    private static final int TITLE_MIN_TEXT_SIZE_SP = 12;

    /**
     * Amount by which the page title font will be reduced, for each step of the
     * reduction process.
     */
    private static final int TITLE_TEXT_SIZE_DECREMENT_SP = 4;

    public interface OnLeadImageLayoutListener {
        void onLayoutComplete(int sequence);
    }

    @NonNull private final PageFragment parentFragment;
    @NonNull private final CommunicationBridge bridge;
    @NonNull private final ObservableWebView webView;

    @NonNull private final ArticleHeaderView articleHeaderView;
    private View image;

    private int displayHeightDp;
    private float faceYOffsetNormalized;
    private float displayDensity;

    public LeadImagesHandler(@NonNull final PageFragment parentFragment,
                             @NonNull CommunicationBridge bridge,
                             @NonNull ObservableWebView webView,
                             @NonNull ArticleHeaderView articleHeaderView) {
        this.articleHeaderView = articleHeaderView;
        this.articleHeaderView.setMenuBarCallback(new MenuBarCallback());
        this.parentFragment = parentFragment;
        this.bridge = bridge;
        this.webView = webView;

        image = articleHeaderView.getImage();

        initDisplayDimensions();

        initWebView();

        initArticleHeaderView();

        // hide ourselves by default
        hide();
    }

    /**
     * Completely hide the lead image view. Useful in case of network errors, etc.
     * The only way to "show" the lead image view is by calling the beginLayout function.
     */
    public void hide() {
        articleHeaderView.hide();
    }

    @Nullable public Bitmap getLeadImageBitmap() {
        return isLeadImageEnabled() ? articleHeaderView.copyImage() : null;
    }

    public boolean isLeadImageEnabled() {
        String thumbUrl = getLeadImageUrl();
        if (!WikipediaApp.getInstance().isImageDownloadEnabled() || displayHeightDp < MIN_SCREEN_HEIGHT_DP) {
            // disable the lead image completely
            return false;
        } else {
            // Enable only if the image is not a GIF, since GIF images are usually mathematical
            // diagrams or animations that won't look good as a lead image.
            // TODO: retrieve the MIME type of the lead image, instead of relying on file name.
            return thumbUrl != null && !thumbUrl.endsWith(".gif");
        }
    }

    public void updateBookmark(boolean bookmarkSaved) {
        articleHeaderView.updateBookmark(bookmarkSaved);
    }

    public void updateNavigate(@Nullable Location geo) {
        articleHeaderView.updateNavigate(geo != null);
    }

    /**
     * Returns the normalized (0.0 to 1.0) vertical focus position of the lead image.
     * A value of 0.0 represents the top of the image, and 1.0 represents the bottom.
     * The "focus position" is currently defined by automatic face detection, but may be
     * defined by other factors in the future.
     *
     * @return Normalized vertical focus position.
     */
    public float getLeadImageFocusY() {
        return faceYOffsetNormalized;
    }

    /**
     * Triggers a chain of events that will lay out the lead image, page title, and other
     * elements, at the end of which the WebView contents may begin to be composed.
     * These events (performed asynchronously) are in the following order:
     * - Dynamically resize the page title TextView and, if necessary, adjust its font size
     * based on the length of our page title.
     * - Dynamically resize the lead image container view and restyle it, if necessary, depending
     * on whether the page contains a lead image, and whether our screen resolution is high
     * enough to warrant a lead image.
     * - Send a "padding" event to the WebView so that any subsequent content that's added to it
     * will be correctly offset to account for the lead image view (or lack of it)
     * - Make the lead image view visible.
     * - Fire a callback to the provided Listener indicating that the rest of the WebView content
     * can now be loaded.
     * - Fetch and display the WikiData description for this page, if available.
     * <p/>
     * Realistically, the whole process will happen very quickly, and almost unnoticeably to the
     * user. But it still needs to be asynchronous because we're dynamically laying out views, and
     * because the padding "event" that we send to the WebView must come before any other content
     * is sent to it, so that the effect doesn't look jarring to the user.
     *
     * @param listener Listener that will receive an event when the layout is completed.
     */
    public void beginLayout(OnLeadImageLayoutListener listener,
                            int sequence) {
        if (getPage() == null) {
            return;
        }

        initDisplayDimensions();

        // set the page title text, and honor any HTML formatting in the title
        loadLeadImage();
        articleHeaderView.setTitle(Html.fromHtml(getPage().getDisplayTitle()));
        articleHeaderView.setLocale(getPage().getTitle().getSite().getLanguageCode());
        articleHeaderView.setPronunciation(getPage().getTitlePronunciationUrl());
        // Set the subtitle, too, so text measurements are accurate.
        layoutWikiDataDescription(getTitle().getDescription());

        // kick off the (asynchronous) laying out of the page title text
        layoutPageTitle((int) (getDimension(R.dimen.titleTextSize)
                / displayDensity), listener, sequence);
    }

    /**
     * Intermediate step in the layout process:
     * Recursive function that will dynamically size down the page title TextView if the page title
     * is too long. Since it's assumed that the overall lead image view is hidden at this stage,
     * this process will be invisible to the user, and will not appear jarring. Once the optimal
     * font size is reached, the next step in the layout process is triggered.
     *
     * @param fontSizeSp Font size to be tested.
     * @param listener   Listener that will receive an event when the layout is completed.
     */
    private void layoutPageTitle(final int fontSizeSp, final OnLeadImageLayoutListener listener, final int sequence) {
        if (!isFragmentAdded()) {
            return;
        }
        // set the font size of the title
        articleHeaderView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp);
        // if we're still not being shown (if the fragment is still being created),
        // then retry after a delay.
        if (articleHeaderView.getTextHeight() == 0) {
            final int postDelay = 50;
            articleHeaderView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    layoutPageTitle(fontSizeSp, listener, sequence);
                }
            }, postDelay);
        } else {
            // give it a chance to redraw, and then see if it fits
            articleHeaderView.post(new Runnable() {
                @Override
                public void run() {
                    if (!isFragmentAdded()) {
                        return;
                    }
                    if (((int) (articleHeaderView.getTextHeight() / displayDensity) > TITLE_MAX_HEIGHT_DP)
                            && (fontSizeSp > TITLE_MIN_TEXT_SIZE_SP)) {
                        int newSize = fontSizeSp - TITLE_TEXT_SIZE_DECREMENT_SP;
                        if (newSize < TITLE_MIN_TEXT_SIZE_SP) {
                            newSize = TITLE_MIN_TEXT_SIZE_SP;
                        }
                        layoutPageTitle(newSize, listener, sequence);
                    } else {
                        // we're done!
                        layoutViews(listener, sequence);
                    }
                }
            });
        }
    }

    /**
     * The final step in the layout process:
     * Apply sizing and styling to our page title and lead image views, based on how large our
     * page title ended up, and whether we should display the lead image.
     *
     * @param listener Listener that will receive an event when the layout is completed.
     */
    private void layoutViews(OnLeadImageLayoutListener listener, int sequence) {
        if (!isFragmentAdded()) {
            return;
        }

        if (isMainPage()) {
            articleHeaderView.hide();
        } else {
            if (!isLeadImageEnabled()) {
                articleHeaderView.showText();
            } else {
                articleHeaderView.showTextImage();
            }
        }

        // tell our listener that it's ok to start loading the rest of the WebView content
        listener.onLayoutComplete(sequence);
    }

    private void updatePadding() {
        int padding;
        if (isMainPage()) {
            padding = Math.round(getContentTopOffsetPx(getActivity()) / displayDensity);
        } else {
            padding = Math.round(articleHeaderView.getHeight() / displayDensity);
        }

        setWebViewPaddingTop(padding);
    }

    private void setWebViewPaddingTop(int padding) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("paddingTop", padding);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        bridge.sendMessage("setPaddingTop", payload);
    }

    /**
     * Final step in the WikiData description process: lay out the description, and animate it
     * into place, along with the page title.
     *
     * @param description WikiData description to be shown.
     */
    private void layoutWikiDataDescription(@Nullable final String description) {
        if (TextUtils.isEmpty(description)) {
            articleHeaderView.setSubtitle(null);
        } else {
            int titleLineCount = articleHeaderView.getLineCount();

            articleHeaderView.setSubtitle(description);

            // Only show the description if it's two lines or less.
            if ((articleHeaderView.getLineCount() - titleLineCount) > 2) {
                articleHeaderView.setSubtitle(null);
            }
        }
    }

    /**
     * Determines and sets displayDensity and displayHeightDp for the lead images layout.
     */
    private void initDisplayDimensions() {
        // preload the display density, since it will be used in a lot of places
        displayDensity = DimenUtil.getDensityScalar();

        int displayHeightPx = DimenUtil.getDisplayHeightPx();

        displayHeightDp = (int) (displayHeightPx / displayDensity);
    }

    private void loadLeadImage() {
        loadLeadImage(getLeadImageUrl());
    }

    /**
     * @param url Nullable URL with no scheme. For example, foo.bar.com/ instead of
     *            http://foo.bar.com/.
     */
    private void loadLeadImage(@Nullable String url) {
        if (!isMainPage() && !TextUtils.isEmpty(url) && isLeadImageEnabled()) {
            String fullUrl = WikipediaApp.getInstance().getNetworkProtocol() + ":" + url;
            articleHeaderView.setImageYScalar(0);
            articleHeaderView.loadImage(fullUrl);
        } else {
            articleHeaderView.loadImage(null);
        }
    }

    /**
     * @return Nullable URL with no scheme. For example, foo.bar.com/ instead of
     * http://foo.bar.com/.
     */
    @Nullable
    private String getLeadImageUrl() {
        return getPage() == null ? null : getPage().getPageProperties().getLeadImageUrl();
    }

    @Nullable
    private Location getGeo() {
        return getPage() == null ? null : getPage().getPageProperties().getGeo();
    }

    private void startKenBurnsAnimation() {
        Animation anim = AnimationUtils.loadAnimation(getActivity(), R.anim.lead_image_zoom);
        image.startAnimation(anim);
    }

    private void initArticleHeaderView() {
        articleHeaderView.setOnImageLoadListener(new ImageLoadListener());
        articleHeaderView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            @SuppressWarnings("checkstyle:parameternumber")
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                updatePadding();
            }
        });
    }

    private void initWebView() {
        webView.addOnScrollChangeListener(articleHeaderView);

        webView.addOnClickListener(new ObservableWebView.OnClickListener() {
            @Override
            public boolean onClick(float x, float y) {
                // if the click event is within the area of the lead image, then the user
                // must have wanted to click on the lead image!
                if (getPage() != null && isLeadImageEnabled() && y < (articleHeaderView.getHeight() - webView.getScrollY())) {
                    String imageName = getPage().getPageProperties().getLeadImageName();
                    if (imageName != null) {
                        PageTitle imageTitle = new PageTitle("File:" + imageName,
                                getTitle().getSite());
                        GalleryActivity.showGallery(getActivity(),
                                parentFragment.getTitleOriginal(), imageTitle,
                                GalleryFunnel.SOURCE_LEAD_IMAGE);
                    }
                    return true;
                }
                return false;
            }
        });
    }

    private boolean isMainPage() {
        return getPage() != null && getPage().isMainPage();
    }

    private PageTitle getTitle() {
        return parentFragment.getTitle();
    }

    @Nullable
    private Page getPage() {
        return parentFragment.getPage();
    }

    private boolean isFragmentAdded() {
        return parentFragment.isAdded();
    }

    private float getDimension(@DimenRes int id) {
        return getResources().getDimension(id);
    }

    private Resources getResources() {
        return getActivity().getResources();
    }

    private FragmentActivity getActivity() {
        return parentFragment.getActivity();
    }

    private class MenuBarCallback extends ArticleMenuBarView.DefaultCallback {
        @Override
        public void onBookmarkClick(boolean bookmarkSaved) {
            if (getPage() == null) {
                return;
            }

            if (bookmarkSaved) {
                saveBookmark();
            } else {
                deleteBookmark();
            }
        }

        @Override
        public void onShareClick() {
            if (getPage() != null) {
                ShareUtil.shareText(getActivity(), getPage().getTitle());
            }
        }

        @Override
        public void onNavigateClick() {
            openGeoIntent();
        }

        private void saveBookmark() {
            WikipediaApp.getInstance().getFunnelManager().getSavedPagesFunnel(getTitle().getSite()).logSaveNew();
            FeedbackUtil.showMessage(getActivity(), R.string.snackbar_saving_page);
            new SavePageTask(WikipediaApp.getInstance(), getTitle(), getPage()) {
                @Override
                public void onFinish(Boolean success) {
                    if (parentFragment.isAdded() && getTitle() != null) {
                        parentFragment.setPageSaved(success);
                        FeedbackUtil.showMessage(getActivity(), getActivity().getString(success
                                ? R.string.snackbar_saved_page_format
                                : R.string.snackbar_saved_page_missing_images, getTitle().getDisplayText()));
                    }
                }
            }.execute();
        }

        private void deleteBookmark() {
            new DeleteSavedPageTask(getActivity(), new SavedPage(getTitle())) {
                @Override
                public void onFinish(Boolean success) {
                    WikipediaApp.getInstance().getFunnelManager().getSavedPagesFunnel(getTitle().getSite()).logDelete();
                    if (parentFragment.isAdded()) {
                        parentFragment.setPageSaved(!success);
                        if (success) {
                            FeedbackUtil.showMessage(getActivity(),
                                    R.string.snackbar_saved_page_deleted);
                        }
                    }
                }
            }.execute();
        }

        private void openGeoIntent() {
            if (getGeo() != null) {
                UriUtil.sendGeoIntent(getActivity(), getGeo(), getTitle().getDisplayText());
            }
        }
    }

    private class ImageLoadListener implements ImageViewWithFace.OnImageLoadListener {
        @Override
        public void onImageLoaded(Bitmap bitmap, @Nullable final PointF faceLocation, @ColorInt final int mainColor) {
            final int bmpHeight = bitmap.getHeight();
            articleHeaderView.post(new Runnable() {
                @Override
                public void run() {
                    if (isFragmentAdded()) {
                        detectFace(bmpHeight, faceLocation);
                        articleHeaderView.setMenuBarColor(mainColor);
                    }
                }
            });
        }

        @Override
        public void onImageFailed() {
            articleHeaderView.resetMenuBarColor();
        }

        private void detectFace(int bmpHeight, @Nullable PointF faceLocation) {
            faceYOffsetNormalized = faceYScalar(bmpHeight, faceLocation);

            float scalar = constrainScalar(faceYOffsetNormalized);

            articleHeaderView.setImageYScalar(scalar);

            // fade in the new image!
            articleHeaderView.crossFadeImage();

            startKenBurnsAnimation();
        }

        private float constrainScalar(float scalar) {
            scalar = Math.max(0, scalar);
            scalar = Math.min(scalar, 1);
            return scalar;
        }

        private float faceYScalar(int bmpHeight, @Nullable PointF faceLocation) {
            final float defaultOffsetScalar = .25f;
            float scalar = defaultOffsetScalar;
            if (faceLocation != null) {
                scalar = faceLocation.y / bmpHeight;
                // TODO: if it is desirable to offset to the nose, replace this arbitrary hardcoded
                //       value with a proportion. FaceDetector.eyesDistance() presumably provides
                //       the interpupillary distance in pixels. We can multiply this measurement by
                //       the proportion of the length of the nose to the IPD.
                scalar += getDimension(R.dimen.face_detection_nose_y_offset) / bmpHeight;
            }
            return scalar;
        }
    }
}

"""pdf_text.page_raster and page_rasters, the way a PDF page becomes
pixels (the AI's page pictures, a GoodNotes sticker, the Logseq export's
area crops): the zoom it is given the drawn region's size for, the box,
straight alpha, the PNG of a raster or of a crop of it, and several pages
of a file opened once."""

import io
import logging

import pymupdf
import pytest
from PIL import Image

from gamma import pdf_text


def _art(width=200, height=100) -> bytes:
    """A one-page PDF of a red disc and a half-transparent blue square on
    nothing: their edges and the square are partly transparent."""
    doc = pymupdf.open()
    page = doc.new_page(width=width, height=height)
    page.draw_circle((60, 50), 30.3, color=None, fill=(0.9, 0.2, 0.2))
    page.draw_rect(pymupdf.Rect(120, 20, 180, 80), color=None, fill=(0, 0.5, 1), fill_opacity=0.5)
    data = doc.tobytes()
    doc.close()
    return data


def test_the_zoom_is_given_the_drawn_regions_size():
    """A function zoom is called with the width and height, in points, of
    what is drawn: the page, or the box's part of it. A number is the zoom."""
    data, asked = _art(), []

    def zoom(width, height):
        asked.append((width, height))
        return 2.0

    raster, pages = pdf_text.page_raster(data, 1, zoom)
    assert pages == 1 and (raster.width, raster.height, raster.mode) == (400, 200, "RGB")
    raster, _ = pdf_text.page_raster(data, 1, zoom, box=(0.5, 0.25, 1.0, 0.75))
    assert (raster.width, raster.height) == (200, 100)
    assert asked == [(200, 100), (100, 50)]
    raster, _ = pdf_text.page_raster(data, 1, 1.5)
    assert (raster.width, raster.height) == (300, 150)


def test_a_page_past_the_end_has_no_raster_and_a_file_that_does_not_open_raises():
    assert pdf_text.page_raster(_art(), 2, 1.0) == (None, 1)
    assert pdf_text.page_raster(_art(), 0, 1.0) == (None, 1)
    with pytest.raises(ValueError, match="; MuPDF: "):
        pdf_text.page_raster(b"not a pdf at all", 1, 1.0)


def test_a_failed_renders_log_line_carries_mupdfs_warnings(caplog):
    with caplog.at_level(logging.WARNING, logger="gamma"):
        assert pdf_text.render_page(b"not a pdf at all", 1) == (None, 0)
    lines = [r.message for r in caplog.records if "page render failed" in r.message]
    assert len(lines) == 1 and "; MuPDF: " in lines[0]


def test_an_empty_box_gives_a_raster_without_pixels():
    """With alpha or without: MuPDF's PAM writer, which copies an alpha
    raster out, refuses a pixmap without pixels."""
    for alpha in (False, True):
        raster, _ = pdf_text.page_raster(_art(), 1, 2.0, box=(0.5, 0.5, 0.5, 0.9), alpha=alpha)
        assert (raster.width, raster.height, raster.data) == (0, 80, b"")


def test_an_alpha_raster_is_straight_rgba_as_mupdfs_own_png():
    """MuPDF keeps an alpha pixmap premultiplied; the raster holds the
    colours as they are, pixel for pixel what MuPDF's own PNG writer
    gives, and its PNG (written here) decodes to them."""
    data = _art()
    raster, _ = pdf_text.page_raster(data, 1, 1.5, alpha=True)
    assert (raster.mode, raster.channels, raster.stride) == ("RGBA", 4, 300 * 4)
    doc = pymupdf.open(stream=data, filetype="pdf")
    mupdf_png = doc[0].get_pixmap(matrix=pymupdf.Matrix(1.5, 1.5), alpha=True).tobytes("png")
    doc.close()
    assert raster.data == Image.open(io.BytesIO(mupdf_png)).tobytes()
    picture = Image.open(io.BytesIO(raster.png()))
    assert (picture.mode, picture.size) == ("RGBA", (300, 150)) and picture.tobytes() == raster.data
    assert picture.getpixel((0, 0)) == (0, 0, 0, 0)  # nothing drawn: transparent
    assert picture.getpixel((90, 75)) == (229, 51, 51, 255)  # the disc
    r, g, b, a = picture.getpixel((225, 75))  # the half-transparent square, its blue not darkened
    assert (r, b) == (0, 255) and 126 <= g <= 128 and 125 <= a <= 128


def test_a_png_crop_is_those_pixels_of_the_raster():
    raster, _ = pdf_text.page_raster(_art(), 1, 1.0)
    whole = Image.open(io.BytesIO(raster.png()))
    crop = Image.open(io.BytesIO(raster.png((30, 20, 150, 90))))
    assert (crop.mode, crop.size) == ("RGB", (120, 70))
    assert crop.tobytes() == whole.crop((30, 20, 150, 90)).tobytes()


def test_page_rasters_draws_the_pages_asked_from_one_open(monkeypatch, caplog):
    """In the order asked, each as page_raster draws it. A page the file
    does not have is skipped, and so is one that fails to draw (logged);
    a file that will not open raises."""
    doc = pymupdf.open()
    for width in (100, 200, 300):
        doc.new_page(width=width, height=50).draw_circle((40, 25), 20, color=None, fill=(0, 0, 1))
    data = doc.tobytes()
    doc.close()
    opens, real_open, real_draw = [], pdf_text._mupdf_open, pdf_text._draw

    def draw(page, *args):
        if page.number == 1:
            raise RuntimeError("a broken page")
        return real_draw(page, *args)

    monkeypatch.setattr(pdf_text, "_mupdf_open", lambda src: opens.append(src) or real_open(src))
    monkeypatch.setattr(pdf_text, "_draw", draw)
    with caplog.at_level(logging.WARNING, logger="gamma"):
        drawn = list(pdf_text.page_rasters(data, [3, 5, 1, 2, 0], 2.0))
    assert [(page_no, raster.width) for page_no, raster in drawn] == [(3, 600), (1, 200)] and len(opens) == 1
    assert drawn[0][1].data == pdf_text.page_raster(data, 3, 2.0)[0].data
    assert [r.message for r in caplog.records] == ["[pdf-text] picture of page 2 failed: a broken page"]
    with pytest.raises(ValueError, match="; MuPDF: "):
        list(pdf_text.page_rasters(b"not a pdf at all", [1], 1.0))

/* RiTune site — theme toggle, scroll reveal, header state. No dependencies. */
(function () {
  "use strict";

  var root = document.documentElement;

  /* ---------------------------------------------------------- theme */
  var toggle = document.getElementById("theme-toggle");

  function applyTheme(t) {
    root.dataset.theme = t;
    if (toggle) {
      toggle.setAttribute(
        "aria-label",
        t === "dark" ? "Switch to light theme" : "Switch to dark theme"
      );
    }
  }

  /* sync the toggle label with the theme set by the inline head script */
  applyTheme(root.dataset.theme === "light" ? "light" : "dark");

  if (toggle) {
    toggle.addEventListener("click", function () {
      var next = root.dataset.theme === "dark" ? "light" : "dark";
      applyTheme(next);
      try { localStorage.setItem("ritune-theme", next); } catch (e) { /* private mode */ }
    });
  }

  /* ------------------------------------------------- header on scroll */
  var header = document.querySelector(".site-header");
  function onScroll() {
    if (header) header.classList.toggle("scrolled", window.scrollY > 8);
  }
  onScroll();
  window.addEventListener("scroll", onScroll, { passive: true });

  /* ---------------------------------------------------- reveal on view */
  var reveals = Array.prototype.slice.call(document.querySelectorAll(".reveal"));
  if ("IntersectionObserver" in window) {
    var io = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry) {
          if (entry.isIntersecting) {
            entry.target.classList.add("in");
            io.unobserve(entry.target);
          }
        });
      },
      { threshold: 0.15, rootMargin: "0px 0px -40px 0px" }
    );
    reveals.forEach(function (el) { io.observe(el); });
  } else {
    reveals.forEach(function (el) { el.classList.add("in"); });
  }

  /* ------------------------------------------------------ footer year */
  var year = document.getElementById("year");
  if (year) year.textContent = String(new Date().getFullYear());
})();
